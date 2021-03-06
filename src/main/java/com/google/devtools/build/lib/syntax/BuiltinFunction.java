// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.syntax;

import com.google.common.base.Preconditions;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.syntax.SkylarkSignatureProcessor.HackHackEitherList;
import com.google.devtools.build.lib.syntax.SkylarkType.SkylarkFunctionType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;

/**
 * A class for Skylark functions provided as builtins by the Skylark implementation
 */
public class BuiltinFunction extends BaseFunction {

  /** ExtraArgKind so you can tweek your function's own calling convention */
  public static enum ExtraArgKind {
    LOCATION,
    SYNTAX_TREE,
    ENVIRONMENT;
  }
  // Predefined system add-ons to function signatures
  public static final ExtraArgKind[] USE_LOC =
      new ExtraArgKind[] {ExtraArgKind.LOCATION};
  public static final ExtraArgKind[] USE_LOC_ENV =
      new ExtraArgKind[] {ExtraArgKind.LOCATION, ExtraArgKind.ENVIRONMENT};
  public static final ExtraArgKind[] USE_AST =
      new ExtraArgKind[] {ExtraArgKind.SYNTAX_TREE};
  public static final ExtraArgKind[] USE_AST_ENV =
      new ExtraArgKind[] {ExtraArgKind.SYNTAX_TREE, ExtraArgKind.ENVIRONMENT};


  // The underlying invoke() method.
  @Nullable private Method invokeMethod;

  // extra arguments required beside signature.
  @Nullable private ExtraArgKind[] extraArgs;

  // The count of arguments in the inner invoke method,
  // to be used as size of argument array by the outer call method.
  private int innerArgumentCount;

  // The returnType of the method.
  private Class<?> returnType;


  /** Create unconfigured function from its name */
  public BuiltinFunction(String name) {
    super(name);
  }

  /** Creates an unconfigured BuiltinFunction with the given name and defaultValues */
  public BuiltinFunction(String name, Iterable<Object> defaultValues) {
    super(name, defaultValues);
  }

  /** Creates a BuiltinFunction with the given name and signature */
  public BuiltinFunction(String name, FunctionSignature signature) {
    super(name, signature);
    configure();
  }

  /** Creates a BuiltinFunction with the given name and signature with values */
  public BuiltinFunction(String name,
      FunctionSignature.WithValues<Object, SkylarkType> signature) {
    super(name, signature);
    configure();
  }

  /** Creates a BuiltinFunction with the given name and signature and extra arguments */
  public BuiltinFunction(String name, FunctionSignature signature, ExtraArgKind[] extraArgs) {
    super(name, signature);
    this.extraArgs = extraArgs;
    configure();
  }

  /** Creates a BuiltinFunction with the given name, signature with values, and extra arguments */
  public BuiltinFunction(String name,
      FunctionSignature.WithValues<Object, SkylarkType> signature, ExtraArgKind[] extraArgs) {
    super(name, signature);
    this.extraArgs = extraArgs;
    configure();
  }


  /** Creates a BuiltinFunction from the given name and a Factory */
  public BuiltinFunction(String name, Factory factory) {
    super(name);
    configure(factory);
  }

  @Override
  protected int getArgArraySize () {
    return innerArgumentCount;
  }

  protected ExtraArgKind[] getExtraArgs () {
    return extraArgs;
  }

  @Override
  @Nullable
  public Object call(Object[] args,
      FuncallExpression ast, Environment env)
      throws EvalException, InterruptedException {
    Preconditions.checkNotNull(ast);
    Preconditions.checkNotNull(env);
    Location loc = ast.getLocation();

    // Add extra arguments, if needed
    if (extraArgs != null) {
      int i = args.length - extraArgs.length;
      for (BuiltinFunction.ExtraArgKind extraArg : extraArgs) {
        switch(extraArg) {
          case LOCATION:
            args[i] = loc;
            break;

          case SYNTAX_TREE:
            args[i] = ast;
            break;

          case ENVIRONMENT:
            args[i] = env;
            break;
        }
        i++;
      }
    }

    Profiler.instance().startTask(ProfilerTask.SKYLARK_BUILTIN_FN,
        this.getClass().getName() + "#" + getName());
    // Last but not least, actually make an inner call to the function with the resolved arguments.
    try {
      env.enterScope(this, ast, env.getGlobals());
      return invokeMethod.invoke(this, args);
    } catch (InvocationTargetException x) {
      Throwable e = x.getCause();
      if (e instanceof EvalException) {
        throw ((EvalException) e).ensureLocation(loc);
      } else if (e instanceof InterruptedException) {
        throw (InterruptedException) e;
      } else if (e instanceof ClassCastException
          || e instanceof ExecutionException
          || e instanceof IllegalStateException) {
        throw new EvalException(loc, "in call to " + getName(), e);
      } else if (e instanceof IllegalArgumentException) {
        throw new EvalException(loc, "Illegal argument in call to " + getName(), e);
      } else {
        throw badCallException(loc, e, args);
      }
    } catch (IllegalArgumentException e) {
      // Either this was thrown by Java itself, or it's a bug
      // To cover the first case, let's manually check the arguments.
      final int len = args.length - ((extraArgs == null) ? 0 : extraArgs.length);
      final Class<?>[] types = invokeMethod.getParameterTypes();
      for (int i = 0; i < args.length; i++) {
        if (args[i] != null && !types[i].isAssignableFrom(args[i].getClass())) {
          String paramName =
              i < len ? signature.getSignature().getNames().get(i) : extraArgs[i - len].name();
          int extraArgsCount = (extraArgs == null) ? 0 : extraArgs.length;
          throw new EvalException(
              loc,
              String.format(
                  "Method %s is not applicable for arguments %s: '%s' is %s, but should be %s",
                  getShortSignature(true), printTypeString(args, args.length - extraArgsCount),
                  paramName, EvalUtils.getDataTypeName(args[i]),
                  EvalUtils.getDataTypeNameFromClass(types[i])));
        }
      }
      throw badCallException(loc, e, args);
    } catch (IllegalAccessException e) {
      throw badCallException(loc, e, args);
    } finally {
      Profiler.instance().completeTask(ProfilerTask.SKYLARK_BUILTIN_FN);
      env.exitScope();
    }
  }

  private IllegalStateException badCallException(Location loc, Throwable e, Object... args) {
    // If this happens, it's a bug in our code.
    return new IllegalStateException(String.format("%s%s (%s)\n"
            + "while calling %s with args %s\nJava parameter types: %s\nSkylark type checks: %s",
            (loc == null) ? "" : loc + ": ",
            e.getClass().getName(), e.getMessage(), this,
            Arrays.asList(args),
            Arrays.asList(invokeMethod.getParameterTypes()),
            signature.getTypes()), e);
  }


  /** Configure the reflection mechanism */
  @Override
  public void configure(SkylarkSignature annotation) {
    Preconditions.checkState(!isConfigured()); // must not be configured yet
    enforcedArgumentTypes = new ArrayList<>();
    this.extraArgs = SkylarkSignatureProcessor.getExtraArgs(annotation);
    this.returnType = annotation.returnType();
    super.configure(annotation);
  }

  // finds the method and makes it accessible (which is needed to find it, and later to use it)
  protected Method findMethod(final String name) {
    Method found = null;
    for (Method method : this.getClass().getDeclaredMethods()) {
      method.setAccessible(true);
      if (name.equals(method.getName())) {
        if (found != null) {
          throw new IllegalArgumentException(String.format(
              "function %s has more than one method named %s", getName(), name));
        }
        found = method;
      }
    }
    if (found == null) {
      throw new NoSuchElementException(String.format(
          "function %s doesn't have a method named %s", getName(), name));
    }
    return found;
  }

  /** Configure the reflection mechanism */
  @Override
  protected void configure() {
    invokeMethod = findMethod("invoke");

    int arguments = signature.getSignature().getShape().getArguments();
    innerArgumentCount = arguments + (extraArgs == null ? 0 : extraArgs.length);
    Class<?>[] parameterTypes = invokeMethod.getParameterTypes();
    Preconditions.checkArgument(innerArgumentCount == parameterTypes.length,
        "bad argument count for %s: method has %s arguments, type list has %s",
        getName(), innerArgumentCount, parameterTypes.length);

    if (enforcedArgumentTypes != null) {
      for (int i = 0; i < arguments; i++) {
        SkylarkType enforcedType = enforcedArgumentTypes.get(i);
        if (enforcedType != null) {
          Class<?> parameterType = parameterTypes[i];
          String msg = String.format(
              "fun %s(%s), param %s, enforcedType: %s (%s); parameterType: %s",
              getName(), signature, signature.getSignature().getNames().get(i),
              enforcedType, enforcedType.getType(), parameterType);
          if (enforcedType instanceof SkylarkType.Simple
              || enforcedType instanceof SkylarkFunctionType) {
            Preconditions.checkArgument(
                enforcedType.getType() == parameterType, msg);
            // No need to enforce Simple types on the Skylark side, the JVM will do it for us.
            enforcedArgumentTypes.set(i, null);
          } else if (enforcedType instanceof SkylarkType.Combination) {
            Preconditions.checkArgument(enforcedType.getType() == parameterType, msg);
          } else {
            Preconditions.checkArgument(
                parameterType == Object.class || parameterType == null, msg);
          }
        }
      }
    }
    // No need for the enforcedArgumentTypes List if all the types were Simple
    enforcedArgumentTypes = FunctionSignature.<SkylarkType>valueListOrNull(enforcedArgumentTypes);

    if (returnType != null) {
      Class<?> type = returnType;
      if (type == HackHackEitherList.class) {
        type = Object.class;
      }
      Class<?> methodReturnType = invokeMethod.getReturnType();
      Preconditions.checkArgument(type == methodReturnType,
          "signature for function %s says it returns %s but its invoke method returns %s",
          getName(), returnType, methodReturnType);
    }
  }

  /** Configure by copying another function's configuration */
  // Alternatively, we could have an extension BuiltinFunctionSignature of FunctionSignature,
  // and use *that* instead of a Factory.
  public void configure(BuiltinFunction.Factory factory) {
    // this function must not be configured yet, but the factory must be
    Preconditions.checkState(!isConfigured());
    Preconditions.checkState(factory.isConfigured(),
        "function factory is not configured for %s", getName());

    this.paramDoc = factory.getParamDoc();
    this.signature = factory.getSignature();
    this.extraArgs = factory.getExtraArgs();
    this.objectType = factory.getObjectType();
    configure();
  }

  /**
   * A Factory allows for a @SkylarkSignature annotation to be provided and processed in advance
   * for a function that will be defined later as a closure (see e.g. in PackageFactory).
   *
   * <p>Each instance of this class must define a method create that closes over some (final)
   * variables and returns a BuiltinFunction.
   */
  public abstract static class Factory extends BuiltinFunction {
    @Nullable private Method createMethod;

    /** Create unconfigured function Factory from its name */
    public Factory(String name) {
      super(name);
    }

    /** Creates an unconfigured function Factory with the given name and defaultValues */
    public Factory(String name, Iterable<Object> defaultValues) {
      super(name, defaultValues);
    }

    @Override
    public void configure() {
      if (createMethod != null) {
        return;
      }
      createMethod = findMethod("create");
    }

    @Override
    public Object call(Object[] args, @Nullable FuncallExpression ast, @Nullable Environment env)
      throws EvalException {
      throw new EvalException(null, "Tried to invoke a Factory for function " + this);
    }

    /** Instantiate the Factory
     * @param args arguments to pass to the create method
     * @return a new BuiltinFunction that closes over the arguments
     */
    public BuiltinFunction apply(Object... args) {
      try {
        return (BuiltinFunction) createMethod.invoke(this, args);
      } catch (InvocationTargetException | IllegalArgumentException | IllegalAccessException e) {
        throw new RuntimeException(String.format(
            "Exception while applying BuiltinFunction.Factory %s: %s",
            this, e.getMessage()), e);
      }
    }
  }
}
