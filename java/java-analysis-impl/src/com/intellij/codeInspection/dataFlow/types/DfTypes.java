// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.dataFlow.types;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInspection.dataFlow.DfaNullability;
import com.intellij.codeInspection.dataFlow.Mutability;
import com.intellij.codeInspection.dataFlow.TypeConstraint;
import com.intellij.codeInspection.dataFlow.TypeConstraints;
import com.intellij.codeInspection.dataFlow.jvm.JvmPsiRangeSetUtil;
import com.intellij.codeInspection.dataFlow.jvm.JvmSpecialField;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.psi.PsiKeyword;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;

/**
 * Commonly used types and factory methods
 */
public final class DfTypes {
  private DfTypes() {}

  /**
   * A type that corresponds to JVM boolean type. Contains two values: true and false
   */
  public static final DfBooleanType BOOLEAN = new DfBooleanType() {
    @Override
    public boolean isSuperType(@NotNull DfType other) {
      return other == BOTTOM || other instanceof DfBooleanType;
    }

    @Override
    public @NotNull DfType join(@NotNull DfType other) {
      if (other instanceof DfBooleanType) return this;
      return TOP;
    }

    @Override
    public @NotNull DfType meet(@NotNull DfType other) {
      if (other == TOP) return this;
      if (other instanceof DfBooleanType) return other;
      return BOTTOM;
    }

    @Override
    public @NotNull DfType tryNegate() {
      return BOTTOM;
    }

    @Override
    public int hashCode() {
      return 345661;
    }

    @Override
    public @NotNull String toString() {
      return PsiKeyword.BOOLEAN;
    }
  };

  /**
   * A true boolean constant
   */
  public static final DfBooleanConstantType TRUE = new DfBooleanConstantType(true);

  /**
   * A false boolean constant
   */
  public static final DfBooleanConstantType FALSE = new DfBooleanConstantType(false);

  /**
   * @param value boolean value
   * @return a boolean constant having given value
   */
  @Contract(pure = true)
  public static @NotNull DfBooleanConstantType booleanValue(boolean value) {
    return value ? TRUE : FALSE;
  }

  /**
   * A type that corresponds to JVM int type
   */
  public static final DfIntType INT = new DfIntRangeType(Objects.requireNonNull(JvmPsiRangeSetUtil.typeRange(PsiType.INT)), null);

  /**
   * Creates a type that represents a subset of int values, clamping values not representable in the JVM int type.
   *
   * @param range range of values. Values that cannot be represented in JVM int type are removed from this range upon creation.
   * @return resulting type. Might be {@link DfType#BOTTOM} if range is empty or all its values are out of the int domain.
   */
  public static @NotNull DfType intRangeClamped(LongRangeSet range) {
    return intRange(range.intersect(DfIntRangeType.FULL_RANGE));
  }

  /**
   * Creates a type that represents a subset of int values.
   *
   * @param range range of values.
   * @return resulting type. Might be {@link DfType#BOTTOM} if range is empty.
   * @throws IllegalArgumentException if range contains values not representable in the JVM int type.
   */
  public static @NotNull DfType intRange(LongRangeSet range) {
    if (range.equals(DfIntRangeType.FULL_RANGE)) return INT;
    if (range.isEmpty()) return DfType.BOTTOM;
    Long value = range.getConstantValue();
    if (value != null) {
      return intValue(Math.toIntExact(value));
    }
    return new DfIntRangeType(range, null);
  }

  static @NotNull DfType intRange(@NotNull LongRangeSet range, @Nullable LongRangeSet wideRange) {
    if (wideRange == null || wideRange.equals(range) || wideRange.isEmpty()) return intRange(range);
    if (range.isEmpty()) {
      return DfType.BOTTOM;
    }
    Long value = range.getConstantValue();
    if (value != null) {
      return new DfIntConstantType(Math.toIntExact(value), wideRange);
    }
    return new DfIntRangeType(range, wideRange);
  }

  /**
   * @param value int value
   * @return a int constant type that contains a given value
   */
  public static @NotNull DfIntConstantType intValue(int value) {
    return new DfIntConstantType(value, null);
  }

  /**
   * A type that corresponds to JVM long type
   */
  public static final DfLongType LONG = new DfLongRangeType(LongRangeSet.all(), null);

  /**
   * Creates a type that represents a subset of long values.
   *
   * @param range range of values.
   * @return resulting type. Might be {@link DfType#BOTTOM} if range is empty.
   */
  public static @NotNull DfType longRange(LongRangeSet range) {
    if (range.equals(LongRangeSet.all())) return LONG;
    if (range.isEmpty()) return DfType.BOTTOM;
    Long value = range.getConstantValue();
    if (value != null) {
      return longValue(value);
    }
    return new DfLongRangeType(range, null);
  }

  static @NotNull DfType longRange(@NotNull LongRangeSet range, @Nullable LongRangeSet wideRange) {
    if (wideRange == null || wideRange.equals(range) || wideRange.isEmpty()) return longRange(range);
    if (range.isEmpty()) {
      return DfType.BOTTOM;
    }
    Long value = range.getConstantValue();
    if (value != null) {
      return new DfLongConstantType(value, wideRange);
    }
    return new DfLongRangeType(range, wideRange);
  }

  /**
   * @param value long value
   * @return a long constant type that contains a given value
   */
  public static @NotNull DfLongConstantType longValue(long value) {
    return new DfLongConstantType(value, null);
  }

  /**
   * A convenience selector method to call {@link #longRange(LongRangeSet)} or {@link #intRangeClamped(LongRangeSet)}
   * @param range range
   * @param isLong whether int or long type should be created
   * @return resulting type.
   */
  public static @NotNull DfType rangeClamped(LongRangeSet range, boolean isLong) {
    return isLong ? longRange(range) : intRangeClamped(range);
  }

  /**
   * A type that corresponds to JVM float type
   */
  public static final DfFloatType FLOAT = new DfFloatType() {
    @Override
    public boolean isSuperType(@NotNull DfType other) {
      return other == BOTTOM || other instanceof DfFloatType;
    }

    @Override
    public @NotNull DfType join(@NotNull DfType other) {
      if (other instanceof DfFloatType) return this;
      return TOP;
    }

    @Override
    public @NotNull DfType meet(@NotNull DfType other) {
      if (other == TOP) return this;
      if (other instanceof DfFloatType) return other;
      return BOTTOM;
    }

    @Override
    public @NotNull DfType tryNegate() {
      return BOTTOM;
    }

    @Override
    public int hashCode() {
      return 521441254;
    }

    @Override
    public @NotNull String toString() {
      return PsiKeyword.FLOAT;
    }
  };
  /**
   * Represents +0.0f and -0.0f at the same time
   */
  public static final DfFloatType FLOAT_ZERO = new DfFloatType() {
    @Override
    public boolean isSuperType(@NotNull DfType other) {
      if (other == BOTTOM || other == this) return true;
      Float constant = other.getConstantOfType(Float.class);
      return constant != null && constant == 0.0f;
    }

    @Override
    public @NotNull DfType join(@NotNull DfType other) {
      if (isSuperType(other)) return this;
      if (other.isSuperType(this)) return other;
      if (other instanceof DfFloatNotValueType) return other.join(this);
      if (other instanceof DfFloatType) return FLOAT;
      return TOP;
    }

    @Override
    public @NotNull DfType meet(@NotNull DfType other) {
      if (isSuperType(other)) return other;
      if (other.isSuperType(this)) return this;
      if (other instanceof DfFloatNotValueType) return other.meet(this);
      return BOTTOM;
    }

    @Override
    public @NotNull DfType tryNegate() {
      return new DfFloatNotValueType(Set.of(0.0f, -0.0f));
    }
  };

  /**
   * @param value float value
   * @return a float constant type that contains a given value
   */
  public static DfFloatConstantType floatValue(float value) {
    return new DfFloatConstantType(value);
  }

  /**
   * A type that corresponds to JVM double type
   */
  public static final DfDoubleType DOUBLE = new DfDoubleType() {
    @Override
    public boolean isSuperType(@NotNull DfType other) {
      return other == BOTTOM || other instanceof DfDoubleType;
    }

    @Override
    public @NotNull DfType join(@NotNull DfType other) {
      if (other instanceof DfDoubleType) return this;
      return TOP;
    }

    @Override
    public @NotNull DfType meet(@NotNull DfType other) {
      if (other == TOP) return this;
      if (other instanceof DfDoubleType) return other;
      return BOTTOM;
    }

    @Override
    public @NotNull DfType tryNegate() {
      return BOTTOM;
    }

    @Override
    public int hashCode() {
      return 5645123;
    }

    @Override
    public @NotNull String toString() {
      return PsiKeyword.DOUBLE;
    }
  };
  /**
   * Represents +0.0 and -0.0 at the same time
   */
  public static final DfDoubleType DOUBLE_ZERO = new DfDoubleType() {
    @Override
    public boolean isSuperType(@NotNull DfType other) {
      if (other == BOTTOM || other == this) return true;
      Double constant = other.getConstantOfType(Double.class);
      return constant != null && constant == 0.0;
    }

    @Override
    public @NotNull DfType join(@NotNull DfType other) {
      if (isSuperType(other)) return this;
      if (other.isSuperType(this)) return other;
      if (other instanceof DfDoubleNotValueType) return other.join(this);
      if (other instanceof DfDoubleType) return DOUBLE;
      return TOP;
    }

    @Override
    public @NotNull DfType meet(@NotNull DfType other) {
      if (isSuperType(other)) return other;
      if (other.isSuperType(this)) return this;
      if (other instanceof DfDoubleNotValueType) return other.meet(this);
      return BOTTOM;
    }

    @Override
    public @NotNull DfType tryNegate() {
      return new DfDoubleNotValueType(Set.of(0.0, -0.0));
    }
  };

  /**
   * @param value double value
   * @return a double constant type that contains a given value
   */
  public static DfDoubleConstantType doubleValue(double value) {
    return new DfDoubleConstantType(value);
  }

  /**
   * A reference type that contains only null reference
   */
  public static final DfNullConstantType NULL = new DfNullConstantType();

  /**
   * A reference type that contains any reference except null
   */
  public static final DfReferenceType NOT_NULL_OBJECT =
    customObject(TypeConstraints.TOP, DfaNullability.NOT_NULL, Mutability.UNKNOWN, null, DfType.BOTTOM);

  /**
   * A reference type that contains any reference or null
   */
  public static final DfReferenceType OBJECT_OR_NULL =
    customObject(TypeConstraints.TOP, DfaNullability.UNKNOWN, Mutability.UNKNOWN, null, DfType.BOTTOM);

  /**
   * A reference type that contains any reference to a local object
   */
  public static final DfReferenceType LOCAL_OBJECT =
    new DfGenericObjectType(Set.of(), TypeConstraints.TOP, DfaNullability.NOT_NULL, Mutability.UNKNOWN,
                            null, DfType.BOTTOM, true);

  /**
   * Returns a custom constant type
   *
   * The following types of the objects are supported:
   * <ul>
   *   <li>Integer/Long/Double/Float/Boolean (will be unboxed)</li>
   *   <li>Character/Byte/Short (will be unboxed and widened to int)</li>
   *   <li>String</li>
   *   <li>{@link com.intellij.psi.PsiEnumConstant} (enum constant value, type must be the corresponding enum type)</li>
   *   <li>{@link com.intellij.psi.PsiField} (a final field that contains a unique value, type must be a type of that field)</li>
   *   <li>{@link PsiType} (java.lang.Class object value, type must be java.lang.Class)</li>
   * </ul>
   *
   * @param constant constant value
   * @param type value type
   * @return a constant type that contains only given constant
   */
  public static @NotNull DfConstantType<?> constant(@Nullable Object constant, @NotNull PsiType type) {
    if (constant == null) return NULL;
    DfConstantType<?> primitiveConstant = primitiveConstantImpl(constant);
    if (primitiveConstant != null) return primitiveConstant;
    return referenceConstant(constant, type);
  }

  /**
   * Returns a custom constant type
   *
   * @param constant constant value
   * @param type a pattern DfType: the constant must be a sub-type of this type
   * @return a constant type that contains only given constant
   */
  public static @NotNull DfConstantType<?> constant(@Nullable Object constant, @NotNull DfType type) {
    if (constant == null) return NULL;
    DfConstantType<?> primitiveConstant = primitiveConstantImpl(constant);
    if (primitiveConstant != null) return primitiveConstant;
    if (!(type instanceof DfReferenceType)) throw new IllegalArgumentException("Not reference type: " + type + "; constant: " + constant);
    return new DfReferenceConstantType(constant, ((DfReferenceType)type).getConstraint(), false);
  }

  /**
   * Returns a non-primitive constant
   *
   * @param constant constant value
   * @param type value type
   * @return a constant type that contains only given constant
   */
  public static @NotNull DfConstantType<?> referenceConstant(@NotNull Object constant, @NotNull PsiType type) {
    return new DfReferenceConstantType(constant, TypeConstraints.instanceOf(type), false);
  }

  /**
   * Returns a primitive constant
   *
   * @param constant constant value
   * @return a constant type that contains only given constant
   * @throws IllegalArgumentException if the supplied constant is not supported primitive constant value
   */
  public static @NotNull DfConstantType<?> primitiveConstant(@NotNull Object constant) {
    DfConstantType<?> res = primitiveConstantImpl(constant);
    if (res == null) {
      throw new IllegalArgumentException("Invalid value supplied: " + constant + "(type: " + constant.getClass() + ")");
    }
    return res;
  }

  private static @Nullable DfConstantType<?> primitiveConstantImpl(@NotNull Object constant) {
    if (constant instanceof Boolean) {
      return booleanValue((Boolean)constant);
    }
    if (constant instanceof Integer || constant instanceof Short || constant instanceof Byte) {
      return intValue(((Number)constant).intValue());
    }
    if (constant instanceof Character) {
      return intValue((Character)constant);
    }
    if (constant instanceof Long) {
      return longValue((Long)constant);
    }
    if (constant instanceof Float) {
      return floatValue((Float)constant);
    }
    if (constant instanceof Double) {
      return doubleValue((Double)constant);
    }
    return null;
  }

  /**
   * @param constant string constant
   * @param stringType string type
   * @return concatenation result string
   */
  public static @NotNull DfConstantType<?> concatenationResult(@NotNull String constant, @NotNull PsiType stringType) {
    return new DfReferenceConstantType(constant, TypeConstraints.exact(stringType), true);
  }

  /**
   * @param type PsiType to get default value of
   * @return a constant that represents a JVM default value of given type (0 for int, false for boolean, etc)
   */
  public static DfConstantType<?> defaultValue(@NotNull PsiType type) {
    if (type instanceof PsiPrimitiveType) {
      switch (type.getCanonicalText()) {
        case "boolean":
          return FALSE;
        case "byte":
        case "char":
        case "short":
        case "int":
          return intValue(0);
        case "long":
          return longValue(0L);
        case "float":
          return floatValue(0F);
        case "double":
          return doubleValue(0D);
      }
    }
    return NULL;
  }

  /**
   * @param type type of the object
   * @param nullability nullability
   * @return a type that references given objects of given type (or it subtypes) and has given nullability
   */
  public static @NotNull DfType typedObject(@Nullable PsiType type, @NotNull Nullability nullability) {
    if (type == null) return DfType.TOP;
    if (type instanceof PsiPrimitiveType) {
      if (type.equals(PsiType.VOID)) return DfType.BOTTOM;
      if (type.equals(PsiType.BOOLEAN)) return BOOLEAN;
      if (type.equals(PsiType.INT)) return INT;
      if (type.equals(PsiType.CHAR) || type.equals(PsiType.SHORT) || type.equals(PsiType.BYTE)){
        return intRange(Objects.requireNonNull(JvmPsiRangeSetUtil.typeRange(type)));
      }
      if (type.equals(PsiType.LONG)) return LONG;
      if (type.equals(PsiType.DOUBLE)) return DOUBLE;
      if (type.equals(PsiType.FLOAT)) return FLOAT;
      if (type.equals(PsiType.NULL)) return NULL;
    }
    TypeConstraint constraint = TypeConstraints.instanceOf(type);
    if (constraint == TypeConstraints.BOTTOM) {
      return nullability == Nullability.NOT_NULL ? DfType.BOTTOM : NULL;
    }
    return new DfGenericObjectType(Set.of(), constraint,
                                   DfaNullability.fromNullability(nullability), Mutability.UNKNOWN, null, DfType.BOTTOM, false);
  }

  /**
   * A low-level method to construct a custom reference type. Should not be normally used. Instead prefer construct a type
   * using a series of {@link DfType#meet(DfType)} calls like
   * <pre>{@code
   * constraint.asDfType()
   *   .meet(mutability.asDfType())
   *   .meet(LOCAL_OBJECT)
   *   .meet(specialField.asDfType(sfType))
   * }</pre>
   *
   *
   * @param constraint type constraint
   * @param nullability nullability, must not be {@link DfaNullability#NULL}
   * @param mutability mutability desired mutability
   * @param jvmSpecialField special field
   * @param sfType type of special field
   * @return a reference type object
   */
  public static DfReferenceType customObject(@NotNull TypeConstraint constraint,
                                             @NotNull DfaNullability nullability,
                                             @NotNull Mutability mutability,
                                             @Nullable JvmSpecialField jvmSpecialField,
                                             @NotNull DfType sfType) {
    if (nullability == DfaNullability.NULL) {
      throw new IllegalArgumentException();
    }
    return new DfGenericObjectType(Set.of(), constraint, nullability, mutability, jvmSpecialField, sfType, false);
  }
}
