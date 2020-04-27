/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.template.soy.jbcsrc;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.template.soy.data.SoyValueProvider;
import com.google.template.soy.exprtree.DataAccessNode;
import com.google.template.soy.exprtree.ExprNode;
import com.google.template.soy.exprtree.ExprRootNode;
import com.google.template.soy.exprtree.NullNode;
import com.google.template.soy.exprtree.OperatorNodes.ConditionalOpNode;
import com.google.template.soy.exprtree.OperatorNodes.NullCoalescingOpNode;
import com.google.template.soy.exprtree.VarRefNode;
import com.google.template.soy.jbcsrc.ExpressionCompiler.BasicExpressionCompiler;
import com.google.template.soy.jbcsrc.restricted.BytecodeUtils;
import com.google.template.soy.jbcsrc.restricted.CodeBuilder;
import com.google.template.soy.jbcsrc.restricted.Expression;
import com.google.template.soy.jbcsrc.restricted.FieldRef;
import com.google.template.soy.jbcsrc.restricted.MethodRef;
import com.google.template.soy.jbcsrc.restricted.SoyExpression;
import com.google.template.soy.jbcsrc.restricted.SoyRuntimeType;
import com.google.template.soy.soytree.defn.LocalVar;
import com.google.template.soy.soytree.defn.TemplateParam;
import java.util.Optional;
import javax.annotation.Nullable;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;

/**
 * Attempts to compile an {@link ExprNode} to an {@link Expression} for a {@link SoyValueProvider}
 * in order to preserve laziness.
 *
 * <p>There are two ways to use this depending on the specific requirements of the caller
 *
 * <ul>
 *   <li>{@link #compileAvoidingBoxing(ExprNode, Label)} attempts to compile the expression to a
 *       {@link SoyValueProvider} but without introducing any unnecessary boxing operations.
 *       Generating detach logic is OK. This case is for print operations, where callers may want to
 *       call {@link SoyValueProvider#renderAndResolve} to incrementally print the value. However,
 *       this is only desirable if the expression is naturally a {@link SoyValueProvider}.
 *   <li>{@link #compileAvoidingDetaches(ExprNode)} attempts to compile the expression to a {@link
 *       SoyValueProvider} with no detach logic. This is for passing data to templates or defining
 *       variables with {@code let} statements. In these cases boxing operations are fine (because
 *       the alternative is to use the {@link LazyClosureCompiler} which necessarily boxes the
 *       expression into a custom SoyValueProvider.
 * </ul>
 *
 * <p>This is used as a basic optimization and as a necessary tool to implement template
 * transclusions. If a template has a parameter {@code foo} then we want to be able to render it via
 * {@link SoyValueProvider#renderAndResolve} so that we can render it incrementally.
 */
final class ExpressionToSoyValueProviderCompiler {
  /**
   * Create an expression compiler that can implement complex detaching logic with the given {@link
   * ExpressionDetacher.Factory}
   */
  static ExpressionToSoyValueProviderCompiler create(
      TemplateVariableManager varManager,
      ExpressionCompiler exprCompiler,
      TemplateParameterLookup variables,
      ExpressionDetacher.Factory detacherFactory) {
    return new ExpressionToSoyValueProviderCompiler(
        varManager, exprCompiler, variables, detacherFactory);
  }

  private final TemplateParameterLookup variables;
  private final ExpressionCompiler exprCompiler;
  private final TemplateVariableManager varManager;
  private final ExpressionDetacher.Factory detacherFactory;

  private ExpressionToSoyValueProviderCompiler(
      TemplateVariableManager varManager,
      ExpressionCompiler exprCompiler,
      TemplateParameterLookup variables,
      ExpressionDetacher.Factory detacherFactory) {
    this.exprCompiler = exprCompiler;
    this.variables = variables;
    this.varManager = varManager;
    this.detacherFactory = detacherFactory;
  }

  /**
   * Compiles the given expression tree to a sequence of bytecode in the current method visitor.
   *
   * <p>If successful, the generated bytecode will resolve to a {@link SoyValueProvider} if it can
   * be done without introducing unnecessary boxing operations. This is intended for situations
   * (like print operations) where calling {@link SoyValueProvider#renderAndResolve} would be better
   * than calling {@link #toString()} and passing directly to the output.
   *
   * <p>TODO(lukes): this method is confusingly named
   */
  Optional<Expression> compileAvoidingBoxing(ExprNode node, Label reattachPoint) {
    checkNotNull(node);
    ExpressionDetacher detacher = detacherFactory.createExpressionDetacher(reattachPoint);
    return new CompilerVisitor(
            variables,
            varManager,
            /*exprCompiler=*/ null,
            exprCompiler.asBasicCompiler(detacher),
            detacher)
        .exec(node);
  }

  /**
   * Compiles the given expression tree to a sequence of bytecode in the current method visitor.
   *
   * <p>If successful, the generated bytecode will resolve to a {@link SoyValueProvider} if it can
   * be done without introducing any detach operations. This is intended for situations where we
   * need to model the expression as a SoyValueProvider to satisfy a contract (e.g. let nodes and
   * params), but we also want to preserve any laziness. So boxing is fine, but detaches are not.
   */
  Optional<Expression> compileAvoidingDetaches(ExprNode node) {
    checkNotNull(node);
    return new CompilerVisitor(
            variables,
            varManager,
            exprCompiler,
            /*detachingExprCompiler=*/ null,
            /*detacher=*/ null)
        .exec(node);
  }

  private static final class CompilerVisitor
      extends EnhancedAbstractExprNodeVisitor<Optional<Expression>> {
    final TemplateParameterLookup variables;
    final TemplateVariableManager varManager;

    // depending on the mode exprCompiler will be null, or detachingExprCompiler/detacher will be
    // null.
    @Nullable final ExpressionCompiler exprCompiler;
    @Nullable final BasicExpressionCompiler detachingExprCompiler;
    @Nullable final ExpressionDetacher detacher;

    CompilerVisitor(
        TemplateParameterLookup variables,
        TemplateVariableManager varManager,
        @Nullable ExpressionCompiler exprCompiler,
        @Nullable BasicExpressionCompiler detachingExprCompiler,
        @Nullable ExpressionDetacher detacher) {
      this.variables = variables;
      checkArgument((exprCompiler == null) != (detachingExprCompiler == null));
      checkArgument((detacher == null) == (detachingExprCompiler == null));
      this.exprCompiler = exprCompiler;
      this.detachingExprCompiler = detachingExprCompiler;
      this.detacher = detacher;
      this.varManager = varManager;
    }

    private boolean allowsBoxing() {
      return exprCompiler != null;
    }

    private boolean allowsDetaches() {
      return detachingExprCompiler != null;
    }

    @Override
    protected final Optional<Expression> visitExprRootNode(ExprRootNode node) {
      return visit(node.getRoot());
    }

    // Primitive value constants

    @Override
    protected Optional<Expression> visitNullNode(NullNode node) {
      // unlike other primitives, this doesn't really count as boxing, just a read of a static
      // constant field. so we always do it
      return Optional.of(FieldRef.NULL_PROVIDER.accessor());
    }

    @Override
    protected Optional<Expression> visitNullCoalescingOpNode(NullCoalescingOpNode node) {
      // All non-trivial ?: will require detaches for the left hand side.
      if (allowsDetaches()) {
        Optional<Expression> maybeLeft = visit(node.getLeftChild());
        Optional<Expression> maybeRight = visit(node.getRightChild());
        // Logging statements get dropped when a value is converted to a SoyValue. If at least one
        // side can be compiled to a SoyValueProvider, there could be logging statements in it, so
        // we need to compile the whole expression to a SoyValueProvider.
        if (maybeLeft.isPresent() || maybeRight.isPresent()) {
          // Get the SoyValueProviders, or box so both left and right are SoyValueProviders.
          Expression right =
              maybeRight.orElseGet(
                  () -> compileToSoyValueProviderWithDetaching(node.getRightChild()));
          Expression left;
          if (maybeLeft.isPresent()) {
            // If left can be compiled to a SoyValueProvider, resolve it to check if it's null.
            final Expression leftSVP = maybeLeft.get();

            // Put the SoyValueProvider on the stack twice since we'll need it later.
            Expression leftDup =
                new Expression(leftSVP.resultType(), leftSVP.features(), leftSVP.location()) {
                  @Override
                  protected void doGen(CodeBuilder cb) {
                    leftSVP.gen(cb); // stack: SVP
                    cb.dup(); // stack: SVP, SVP
                  }
                };
            // Resolve the provider, so we can check if it's null.
            final Expression resolved =
                detacher
                    .resolveSoyValueProvider(leftDup)
                    .checkedCast(
                        SoyRuntimeType.getBoxedType(node.getLeftChild().getType()).runtimeType());
            // But throw away the resolved value (since it won't have logging calls in it) and
            // instead use the extra SoyValueProvider on the stack from before.
            left =
                new Expression(leftSVP.resultType(), leftSVP.features(), leftSVP.location()) {
                  @Override
                  protected void doGen(CodeBuilder cb) {
                    resolved.gen(cb); // stack: SVP, SV
                    cb.pop(); // stack: SVP
                  }
                };
          } else {
            // If left cannot be compiled to a SoyValueProvider, compile it to a SoyValue and box it
            // into a SoyValueProvider.
            left = compileToSoyValueProviderWithDetaching(node.getLeftChild());
          }
          // Convert left to null if it's a SoyValueProvider wrapping null, for the null check
          // below.
          left = MethodRef.SOY_VALUE_PROVIDER_OR_NULL.invoke(left);

          return Optional.of(BytecodeUtils.firstNonNull(left, right));
        }
      }
      return visitExprNode(node);
    }

    private Expression compileToSoyValueProviderWithDetaching(ExprNode expr) {
      return detachingExprCompiler.compile(expr).boxAsSoyValueProvider();
    }

    @Override
    protected final Optional<Expression> visitConditionalOpNode(ConditionalOpNode node) {
      if (allowsDetaches()) {
        Optional<Expression> trueBranch = visit(node.getChild(1));
        Optional<Expression> falseBranch = visit(node.getChild(2));
        // Compile to a SoyValueProvider if either side can be compiled to a SoyValueProvider. The
        // SoyValueProvider side(s) may have logging statements in them, so need to stay
        // SoyValueProviders, otherwise the logging statements will get dropped.
        if (trueBranch.isPresent() || falseBranch.isPresent()) {
          Expression condition = detachingExprCompiler.compile(node.getChild(0)).coerceToBoolean();
          return Optional.of(
              BytecodeUtils.ternary(
                  condition,
                  trueBranch.orElseGet(
                      () -> compileToSoyValueProviderWithDetaching(node.getChild(1))),
                  falseBranch.orElseGet(
                      () -> compileToSoyValueProviderWithDetaching(node.getChild(2)))));
        } else {
          return Optional.empty();
        }
      }
      return visitExprNode(node);
    }

    @Override
    Optional<Expression> visitForLoopVar(VarRefNode varRef, LocalVar local) {
      Expression loopVar = variables.getLocal(local);
      if (loopVar.resultType().equals(Type.LONG_TYPE)) {
        // this happens in foreach loops over ranges
        if (allowsBoxing()) {
          return Optional.of(SoyExpression.forInt(loopVar).box());
        }
        return Optional.empty();
      } else {
        return Optional.of(loopVar);
      }
    }

    @Override
    Optional<Expression> visitParam(VarRefNode varRef, TemplateParam param) {
      return Optional.of(variables.getParam(param));
    }

    @Override
    Optional<Expression> visitLetNodeVar(VarRefNode varRef, LocalVar local) {
      return Optional.of(variables.getLocal(local));
    }

    @Override
    protected Optional<Expression> visitDataAccessNode(DataAccessNode node) {
      // TODO(lukes): implement special case for allowsDetaches().  The complex part will be sharing
      // null safety access logic with the ExpressionCompiler
      return visitExprNode(node);
    }

    @Override
    protected final Optional<Expression> visitExprNode(ExprNode node) {
      if (allowsBoxing()) {
        Optional<SoyExpression> compileWithNoDetaches = exprCompiler.compileWithNoDetaches(node);
        if (compileWithNoDetaches.isPresent()) {
          return Optional.of(compileWithNoDetaches.get().boxAsSoyValueProvider());
        }
      }
      return Optional.empty();
    }
  }
}
