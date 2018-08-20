/*
 * Copyright 2018 Google Inc.
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
package com.google.template.soy.jssrc.internal;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.CaseFormat;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.template.soy.base.SourceLocation;
import com.google.template.soy.base.internal.BaseUtils;
import com.google.template.soy.error.ErrorReporter;
import com.google.template.soy.error.SoyErrorKind;
import com.google.template.soy.error.SoyErrorKind.StyleAllowance;
import com.google.template.soy.jssrc.SoyJsSrcOptions;
import com.google.template.soy.jssrc.dsl.CodeChunk;
import com.google.template.soy.jssrc.dsl.CodeChunkUtils;
import com.google.template.soy.jssrc.dsl.Expression;
import com.google.template.soy.jssrc.dsl.GoogRequire;
import com.google.template.soy.jssrc.restricted.JsExpr;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptPluginContext;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValue;
import com.google.template.soy.plugin.javascript.restricted.JavaScriptValueFactory;
import com.google.template.soy.plugin.javascript.restricted.SoyJavaScriptSourceFunction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implementation of {@link JavaScriptValueFactory} that delegates to the {@link Expression} API.
 */
final class JavaScriptValueFactoryImpl extends JavaScriptValueFactory {
  private static final JavaScriptValueImpl ERROR_VALUE =
      new JavaScriptValueImpl(
          Expression.fromExpr(
              new JsExpr(
                  "(function(){throw new Error('if you see this, the soy compiler has swallowed "
                      + "an error :-(');})()",
                  Integer.MAX_VALUE),
              ImmutableList.<GoogRequire>of()));

  private static final SoyErrorKind NULL_RETURN =
      SoyErrorKind.of(
          formatPlain("{2}.applyForJavaScriptSource returned null."),
          StyleAllowance.NO_PUNCTUATION);

  private static final SoyErrorKind UNEXPECTED_ERROR =
      SoyErrorKind.of(formatPlain("{2}"), StyleAllowance.NO_PUNCTUATION);

  private static String formatPlain(String innerFmt) {
    return "Error in plugin implementation for function ''{0}''."
        + "\n"
        + innerFmt
        + "\nPlugin implementation: {1}";
  }

  private final SoyJsSrcOptions jsSrcOptions;
  private final CodeChunk.Generator codeGenerator;
  private final ErrorReporter reporter;
  private final JavaScriptPluginContext context =
      new JavaScriptPluginContext() {
        @Override
        public JavaScriptValue getBidiDir() {
          if (jsSrcOptions.getBidiGlobalDir() == 0) {
            if (!jsSrcOptions.getUseGoogIsRtlForBidiGlobalDir()) {
              throw new RuntimeException("no known bidi dir");
            }
            return new JavaScriptValueImpl(
                Expression.ifExpression(JsRuntime.SOY_IS_LOCALE_RTL, Expression.number(-1))
                    .setElse(Expression.number(1))
                    .build(codeGenerator));
          }
          return new JavaScriptValueImpl(Expression.number(jsSrcOptions.getBidiGlobalDir()));
        }
      };

  JavaScriptValueFactoryImpl(
      SoyJsSrcOptions jsSrcOptions, CodeChunk.Generator codeGenerator, ErrorReporter reporter) {
    this.jsSrcOptions = jsSrcOptions;
    this.codeGenerator = codeGenerator;
    this.reporter = reporter;
  }

  Expression applyFunction(
      SourceLocation location, String name, SoyJavaScriptSourceFunction fn, List<Expression> args) {
    JavaScriptValueImpl result;
    try {
      result = (JavaScriptValueImpl) fn.applyForJavaScriptSource(this, wrapParams(args), context);
      if (result == null) {
        report(location, name, fn, NULL_RETURN, fn.getClass().getSimpleName());
        result = ERROR_VALUE;
      }
    } catch (Throwable t) {
      BaseUtils.trimStackTraceTo(t, getClass());
      report(location, name, fn, UNEXPECTED_ERROR, Throwables.getStackTraceAsString(t));
      result = ERROR_VALUE;
    }
    return result.impl;
  }

  private void report(
      SourceLocation location,
      String name,
      SoyJavaScriptSourceFunction fn,
      SoyErrorKind error,
      Object... additionalArgs) {
    Object[] args = new Object[additionalArgs.length + 2];
    args[0] = name;
    args[1] = fn.getClass().getName();
    System.arraycopy(additionalArgs, 0, args, 2, additionalArgs.length);
    reporter.report(location, error, args);
  }

  @Override
  public JavaScriptValue callModuleFunction(
      String moduleName, String functionName, JavaScriptValue... params) {
    Expression function;
    if (jsSrcOptions.shouldGenerateGoogModules()) {
      String alias =
          "$"
              + CaseFormat.LOWER_UNDERSCORE.to(
                  CaseFormat.LOWER_CAMEL, moduleName.replace('.', '_'));
      function = GoogRequire.createWithAlias(moduleName, alias).dotAccess(functionName);
    } else {
      function = GoogRequire.create(moduleName).googModuleGet().dotAccess(functionName);
    }
    return new JavaScriptValueImpl(function.call(unwrapParams(Arrays.asList(params))));
  }

  @Override
  public JavaScriptValue callNamespaceFunction(
      String googProvide, String fullFunctionName, JavaScriptValue... params) {
    checkArgument(
        fullFunctionName.startsWith(googProvide)
            && (fullFunctionName.length() == googProvide.length()
                || fullFunctionName.charAt(googProvide.length()) == '.'),
        "expected '%s' to be in the namespace of '%s'. '%s' should be fully qualified",
        fullFunctionName,
        googProvide,
        fullFunctionName);
    GoogRequire require = GoogRequire.create(googProvide);
    Expression function;
    if (fullFunctionName.length() == googProvide.length()) {
      function = require.reference();
    } else {
      String suffix = fullFunctionName.substring(googProvide.length() + 1);
      Expression expr = null;
      for (String part : Splitter.on('.').splitToList(suffix)) {
        if (expr == null) {
          expr = require.dotAccess(part);
        } else {
          expr = expr.dotAccess(part);
        }
      }
      function = expr;
    }
    return new JavaScriptValueImpl(function.call(unwrapParams(Arrays.asList(params))));
  }

  @Override
  public JavaScriptValueImpl unsafeUncheckedExpression(String expr) {
    return new JavaScriptValueImpl(
        Expression.fromExpr(new JsExpr(expr, /*precedence=*/ 0), ImmutableList.<GoogRequire>of()));
  }

  @Override
  public JavaScriptValueImpl constant(long num) {
    return new JavaScriptValueImpl(Expression.number(num));
  }

  @Override
  public JavaScriptValueImpl constant(double num) {
    return new JavaScriptValueImpl(Expression.number(num));
  }

  @Override
  public JavaScriptValueImpl constant(String str) {
    return new JavaScriptValueImpl(Expression.stringLiteral(str));
  }

  @Override
  public JavaScriptValueImpl constant(boolean bool) {
    return new JavaScriptValueImpl(bool ? Expression.LITERAL_TRUE : Expression.LITERAL_FALSE);
  }

  private static List<Expression> unwrapParams(List<JavaScriptValue> params) {
    List<Expression> exprs = new ArrayList<>(params.size());
    for (JavaScriptValue v : params) {
      exprs.add(((JavaScriptValueImpl) v).impl);
    }
    return exprs;
  }

  private static List<JavaScriptValue> wrapParams(List<Expression> params) {
    List<JavaScriptValue> exprs = new ArrayList<>(params.size());
    for (Expression e : params) {
      exprs.add(new JavaScriptValueImpl(e));
    }
    return exprs;
  }

  @VisibleForTesting
  static final class JavaScriptValueImpl implements JavaScriptValue {
    @VisibleForTesting final Expression impl;

    JavaScriptValueImpl(Expression impl) {
      this.impl = checkNotNull(impl);
    }

    @Override
    public JavaScriptValueImpl isNonNull() {
      return new JavaScriptValueImpl(impl.doubleNotEquals(Expression.LITERAL_NULL));
    }

    @Override
    public JavaScriptValueImpl isNull() {
      return new JavaScriptValueImpl(impl.doubleEquals(Expression.LITERAL_NULL));
    }

    @Override
    public Optional<String> asStringLiteral() {
      return impl.asStringLiteral();
    }

    @Override
    public JavaScriptValueImpl coerceToString() {
      return new JavaScriptValueImpl(
          CodeChunkUtils.concatChunksForceString(ImmutableList.of(impl)));
    }

    @Override
    public JavaScriptValueImpl invokeMethod(String methodName, JavaScriptValue... args) {
      return new JavaScriptValueImpl(
          impl.dotAccess(methodName).call(unwrapParams(Arrays.asList(args))));
    }

    @Override
    public String toString() {
      return impl.getCode();
    }
  }
}