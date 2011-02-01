package org.jetbrains.jet.lang.types;

import org.jetbrains.annotations.NotNull;

/**
 * @author abreslav
 */
public class TypeProjection {
    private final Variance projection;
    private final Type type;

    public TypeProjection(@NotNull Variance projection, @NotNull Type type) {
        this.projection = projection;
        this.type = type;
    }

    public TypeProjection(Type type) {
        this(Variance.INVARIANT, type);
    }

    public Variance getProjectionKind() {
        return projection;
    }

    public Type getType() {
        return type;
    }

    @Override
    public String toString() {
        if (projection == Variance.INVARIANT) {
            return type + "";
        }
        return projection + " " + type;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        TypeProjection that = (TypeProjection) o;

        if (projection != that.projection) return false;
        if (type != null ? !type.equals(that.type) : that.type != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = projection != null ? projection.hashCode() : 0;
        result = 31 * result + (type != null ? type.hashCode() : 0);
        return result;
    }
}
