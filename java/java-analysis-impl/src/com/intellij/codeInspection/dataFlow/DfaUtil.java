// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.dataFlow.java.JavaDfaInstructionVisitor;
import com.intellij.codeInspection.dataFlow.jvm.JvmPsiRangeSetUtil;
import com.intellij.codeInspection.dataFlow.jvm.JvmSpecialField;
import com.intellij.codeInspection.dataFlow.lang.DfaInterceptor;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.DfPrimitiveType;
import com.intellij.codeInspection.dataFlow.types.DfReferenceType;
import com.intellij.codeInspection.dataFlow.types.DfType;
import com.intellij.codeInspection.dataFlow.types.DfTypes;
import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.codeInspection.dataFlow.value.DfaWrappedValue;
import com.intellij.codeInspection.dataFlow.value.RelationType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.controlFlow.DefUseUtil;
import com.intellij.psi.impl.source.resolve.JavaResolveUtil;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiPrecedenceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.ig.psiutils.ParenthesesUtils;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiPredicate;

import static com.intellij.psi.CommonClassNames.JAVA_UTIL_COLLECTIONS;
import static com.intellij.util.ObjectUtils.tryCast;

/**
 * @author Gregory.Shrago
 */
public final class DfaUtil {
  public static @NotNull Collection<PsiExpression> getVariableValues(@Nullable PsiVariable variable, @Nullable PsiElement context) {
    if (variable == null || context == null) return Collections.emptyList();

    final PsiCodeBlock codeBlock = tryCast(getEnclosingCodeBlock(variable, context), PsiCodeBlock.class);
    if (codeBlock == null) return Collections.emptyList();
    PsiElement[] defs = DefUseUtil.getDefs(codeBlock, variable, context);

    List<PsiExpression> results = new ArrayList<>();
    for (PsiElement def : defs) {
      if (def instanceof PsiLocalVariable) {
        ContainerUtil.addIfNotNull(results, ((PsiLocalVariable)def).getInitializer());
      }
      else if (def instanceof PsiReferenceExpression) {
        PsiAssignmentExpression assignment = tryCast(def.getParent(), PsiAssignmentExpression.class);
        if(assignment != null && assignment.getLExpression() == def) {
          ContainerUtil.addIfNotNull(results, unrollConcatenation(assignment, variable, codeBlock));
        }
      }
      else if (def instanceof PsiExpression) {
        results.add((PsiExpression)def);
      }
    }
    return results;
  }

  private static PsiElement getEnclosingCodeBlock(final PsiVariable variable, final PsiElement context) {
    PsiElement codeBlock;
    if (variable instanceof PsiParameter) {
      codeBlock = ((PsiParameter)variable).getDeclarationScope();
      if (codeBlock instanceof PsiMethod) {
        codeBlock = ((PsiMethod)codeBlock).getBody();
      }
    }
    else if (variable instanceof PsiLocalVariable) {
      codeBlock = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class);
    }
    else {
      codeBlock = DfaPsiUtil.getTopmostBlockInSameClass(context);
    }
    while (codeBlock != null) {
      PsiAnonymousClass anon = PsiTreeUtil.getParentOfType(codeBlock, PsiAnonymousClass.class);
      if (anon == null) break;
      codeBlock = PsiTreeUtil.getParentOfType(anon, PsiCodeBlock.class);
    }
    return codeBlock;
  }

  private static PsiExpression unrollConcatenation(PsiAssignmentExpression assignment, PsiVariable variable, PsiCodeBlock block) {
    List<PsiExpression> operands = new ArrayList<>();
    while (true) {
      if (assignment == null) return null;
      PsiExpression rExpression = assignment.getRExpression();
      if (rExpression == null) return null;
      operands.add(rExpression);
      IElementType type = assignment.getOperationTokenType();
      if (type.equals(JavaTokenType.EQ)) break;
      if (!type.equals(JavaTokenType.PLUSEQ)) {
        return null;
      }
      PsiElement[] previous = DefUseUtil.getDefs(block, variable, assignment);
      if (previous.length != 1) return null;
      PsiElement def = previous[0];
      if (def instanceof PsiLocalVariable) {
        PsiExpression initializer = ((PsiLocalVariable)def).getInitializer();
        if (initializer == null) return null;
        operands.add(initializer);
        break;
      }
      else if (def instanceof PsiReferenceExpression) {
        assignment = tryCast(def.getParent(), PsiAssignmentExpression.class);
      }
      else return null;
    }
    if (operands.size() == 1) {
      return operands.get(0);
    }
    return JavaPsiFacade.getElementFactory(block.getProject()).createExpressionFromText(
      StreamEx.ofReversed(operands).map(op -> ParenthesesUtils.getText(op, PsiPrecedenceUtil.ADDITIVE_PRECEDENCE)).joining("+"),
      assignment);
  }

  /**
   * @deprecated use {@link NullabilityUtil#getExpressionNullability(PsiExpression, boolean)}
   * Note that variable parameter is not used at all now.
   */
  @ApiStatus.ScheduledForRemoval(inVersion = "2020.2")
  @Deprecated
  public static @NotNull Nullability checkNullability(final @Nullable PsiVariable variable, final @Nullable PsiElement context) {
    if (context instanceof PsiExpression) {
      return NullabilityUtil.getExpressionNullability((PsiExpression)context, true);
    }
    return Nullability.UNKNOWN;
  }

  public static @NotNull Collection<PsiExpression> getPossibleInitializationElements(@NotNull PsiElement qualifierExpression) {
    if (qualifierExpression instanceof PsiMethodCallExpression) {
      return Collections.singletonList((PsiMethodCallExpression)qualifierExpression);
    }
    if (qualifierExpression instanceof PsiReferenceExpression) {
      final PsiElement targetElement = ((PsiReferenceExpression)qualifierExpression).resolve();
      if (!(targetElement instanceof PsiVariable)) {
        return Collections.emptyList();
      }
      Collection<PsiExpression> variableValues = getVariableValues((PsiVariable)targetElement, qualifierExpression);
      if (variableValues.isEmpty()) {
        return DfaPsiUtil.getVariableAssignmentsInFile((PsiVariable)targetElement, false, qualifierExpression);
      }
      return variableValues;
    }
    if (qualifierExpression instanceof PsiLiteralExpression) {
      return Collections.singletonList((PsiLiteralExpression)qualifierExpression);
    }
    return Collections.emptyList();
  }

  public static @NotNull Nullability inferMethodNullability(PsiMethod method) {
    if (PsiUtil.resolveClassInType(method.getReturnType()) == null) {
      return Nullability.UNKNOWN;
    }

    return inferBlockNullability(method, suppressNullable(method));
  }

  private static boolean suppressNullable(PsiMethod method) {
    if (method.getParameterList().isEmpty()) return false;

    for (StandardMethodContract contract : JavaMethodContractUtil.getMethodContracts(method)) {
      if (contract.getReturnValue().isNull()) {
        return true;
      }
    }
    return false;
  }

  public static @NotNull Nullability inferLambdaNullability(PsiLambdaExpression lambda) {
    if (LambdaUtil.getFunctionalInterfaceReturnType(lambda) == null) {
      return Nullability.UNKNOWN;
    }

    return inferBlockNullability(lambda, false);
  }

  private static @NotNull Nullability inferBlockNullability(@NotNull PsiParameterListOwner owner, boolean suppressNullable) {
    PsiElement body = owner.getBody();
    if (body == null) return Nullability.UNKNOWN;

    final DataFlowRunner dfaRunner = new DataFlowRunner(owner.getProject());

    final class BlockNullabilityInterceptor implements DfaInterceptor<PsiExpression> {
      boolean hasNulls = false;
      boolean hasNotNulls = false;
      boolean hasUnknowns = false;

      @Override
      public void beforeValueReturn(@NotNull DfaValue value,
                                    @Nullable PsiExpression expression,
                                    @NotNull PsiElement context,
                                    @NotNull DfaMemoryState state) {
        if (context == owner && expression != null) {
          if (TypeConversionUtil.isPrimitiveAndNotNull(expression.getType()) || state.isNotNull(value)) {
            hasNotNulls = true;
          }
          else if (state.isNull(value)) {
            hasNulls = true;
          }
          else {
            hasUnknowns = true;
          }
        }
      }
    }
    var interceptor = new BlockNullabilityInterceptor();
    final RunnerResult rc = dfaRunner.analyzeMethod(body, new JavaDfaInstructionVisitor(interceptor));

    if (rc == RunnerResult.OK) {
      if (interceptor.hasNulls) {
        return suppressNullable ? Nullability.UNKNOWN : Nullability.NULLABLE;
      }
      if (interceptor.hasNotNulls && !interceptor.hasUnknowns) {
        return Nullability.NOT_NULL;
      }
    }

    return Nullability.UNKNOWN;
  }

  public static boolean hasImplicitImpureSuperCall(PsiClass aClass, PsiMethod constructor) {
    PsiClass superClass = aClass.getSuperClass();
    if (superClass == null) return false;
    PsiElement superCtor = JavaResolveUtil.resolveImaginarySuperCallInThisPlace(constructor, constructor.getProject(), superClass);
    if (!(superCtor instanceof PsiMethod)) return false;
    return !JavaMethodContractUtil.isPure((PsiMethod)superCtor);
  }

  /**
   * Returns a surrounding PSI element which should be analyzed via DFA
   * (e.g. passed to {@link DataFlowRunner#analyzeMethodRecursively(PsiElement, InstructionVisitor)}) to cover
   * given expression.
   *
   * @param expression expression to cover
   * @return a dataflow context; null if no applicable context found.
   */
  static @Nullable PsiElement getDataflowContext(PsiExpression expression) {
    PsiMember member = PsiTreeUtil.getParentOfType(expression, PsiMember.class);
    while (member instanceof PsiAnonymousClass && PsiTreeUtil.isAncestor(((PsiAnonymousClass)member).getArgumentList(), expression, true)) {
      member = PsiTreeUtil.getParentOfType(member, PsiMember.class);
    }
    if (member instanceof PsiField || member instanceof PsiClassInitializer) return member.getContainingClass();
    if (member instanceof PsiMethod) {
      return ((PsiMethod)member).isConstructor() ? member.getContainingClass() : ((PsiMethod)member).getBody();
    }
    return null;
  }

  /**
   * Tries to evaluate boolean condition using dataflow analysis.
   * Currently is limited to comparisons like {@code a > b} and constant expressions.
   *
   * @param condition condition to evaluate
   * @return evaluated value or null if cannot be evaluated
   */
  public static @Nullable Boolean evaluateCondition(@Nullable PsiExpression condition) {
    CommonDataflow.DataflowResult result = CommonDataflow.getDataflowResult(condition);
    if (result == null) return null;
    return tryCast(ContainerUtil.getOnlyItem(result.getExpressionValues(condition)), Boolean.class);
  }

  public static DfaValue boxUnbox(DfaValue value, @Nullable PsiType type) {
    return boxUnbox(value, DfTypes.typedObject(type, Nullability.UNKNOWN));
  }

  public static DfaValue boxUnbox(DfaValue value, @NotNull DfType type) {
    if (TypeConstraint.fromDfType(type).isPrimitiveWrapper()) {
      if (value.getDfType() instanceof DfPrimitiveType) {
        return value.getFactory().getWrapperFactory().createWrapper(type.meet(DfTypes.NOT_NULL_OBJECT), JvmSpecialField.UNBOX, value);
      }
    }
    if (type instanceof DfPrimitiveType) {
      if (value.getDfType() instanceof DfPrimitiveType) {
        PsiPrimitiveType psiType = ((DfPrimitiveType)type).getPsiType();
        DfPrimitiveType valueType = (DfPrimitiveType)value.getDfType();
        if (!valueType.getPsiType().equals(psiType)) {
          return value.getFactory().fromDfType(valueType.castTo(psiType));
        }
      }
      if (value instanceof DfaWrappedValue || TypeConstraint.fromDfType(value.getDfType()).isPrimitiveWrapper()) {
        return JvmSpecialField.UNBOX.createValue(value.getFactory(), value);
      }
      if (value.getDfType() instanceof DfReferenceType) {
        return value.getFactory().fromDfType(type);
      }
    }
    return value;
  }

  public static @NotNull List<? extends MethodContract> addRangeContracts(@Nullable PsiMethod method,
                                                                          @NotNull List<? extends MethodContract> contracts) {
    if (method == null) return contracts;
    PsiParameter[] parameters = method.getParameterList().getParameters();
    List<MethodContract> rangeContracts = new ArrayList<>();
    for (int i = 0; i < parameters.length; i++) {
      PsiParameter parameter = parameters[i];
      LongRangeSet fromType = JvmPsiRangeSetUtil.typeRange(parameter.getType());
      if (fromType == null) continue;
      LongRangeSet fromAnnotation = JvmPsiRangeSetUtil.fromPsiElement(parameter);
      if (fromAnnotation.min() > fromType.min()) {
        MethodContract contract = MethodContract.singleConditionContract(
          ContractValue.argument(i), RelationType.LT, ContractValue.constant(fromAnnotation.min(), parameter.getType()),
          ContractReturnValue.fail());
        rangeContracts.add(contract);
      }
      if (fromAnnotation.max() < fromType.max()) {
        MethodContract contract = MethodContract.singleConditionContract(
          ContractValue.argument(i), RelationType.GT, ContractValue.constant(fromAnnotation.max(), parameter.getType()),
          ContractReturnValue.fail());
        rangeContracts.add(contract);
      }
    }
    return ContainerUtil.concat(rangeContracts, contracts);
  }

  public static boolean isEmptyCollectionConstantField(@Nullable PsiVariable var) {
    if (!(var instanceof PsiField)) return false;
    PsiField field = (PsiField)var;
    return field.getName().startsWith("EMPTY_") && field.getContainingClass() != null &&
           JAVA_UTIL_COLLECTIONS.equals(field.getContainingClass().getQualifiedName());
  }

  public static boolean isNaN(Object value) {
    return value instanceof Double && ((Double)value).isNaN() ||
           value instanceof Float && ((Float)value).isNaN();
  }

  /**
   * @param poset input poset (mutable)
   * @param predicate non-strict partial order over the input poset
   * @param <T> type of poset elements
   * @return the longest strong upwards antichain contained in the poset (input poset object with some elements removed)
   */
  public static <T, C extends Collection<T>> C upwardsAntichain(@NotNull C poset, @NotNull BiPredicate<T, T> predicate) {
    for (Iterator<T> iterator = poset.iterator(); iterator.hasNext(); ) {
      T left = iterator.next();
      for (T right : poset) {
        ProgressManager.checkCanceled();
        if (right != left && predicate.test(left, right)) {
          iterator.remove();
          break;
        }
      }
    }
    return poset;
  }
}
