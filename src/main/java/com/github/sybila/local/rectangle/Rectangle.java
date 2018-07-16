package com.github.sybila.local.rectangle;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Rectangle is simply a double array of length 2*|params|.
 *
 * Note that every rectangle is considered immutable once it is created!
 *
 * An empty rectangle/empty set is represented using null.
 */
public final class Rectangle {

    // Only provides static methods.
    private Rectangle() {}

    private final static ThreadLocal<double[]> workArray = new ThreadLocal<>();

    static void initFromDim(int dim) {
        workArray.set(new double[2*dim]);
    }

    /**
     * Compute intersection of two rectangles. Intersection is always one valid rectangle unless
     * it is empty, in which case it's null.
     */
    @Nullable
    static double[] intersect(@Nullable double[] a, @Nullable double[] b) {
        if (a == null || b == null) return null;
        final int dim = a.length / 2;
        final double[] result = workArray.get();
        for (int i=0; i < dim; i++) {
            final int iL = 2*i;
            final int iH = iL + 1;
            final double low = Math.max(a[iL], b[iL]);
            final double high = Math.min(a[iH], b[iH]);
            if (low >= high) {
                return null;
            }
            result[iL] = low;
            result[iH] = high;
        }
        // TODO: test if this actually helps or not
        if (Arrays.equals(a, result)) return a;
        if (Arrays.equals(b, result)) return b;
        return Arrays.copyOf(result, result.length);
    }

    /**
     * True if parent rectangle fully contains child rectangle.
     */
    static boolean encloses(@Nullable double[] parent, @Nullable double[] child) {
        if (parent == null) return false;
        if (child == null) return true;
        final int dim = parent.length / 2;
        for (int i=0; i < dim; i++) {
            final int iL = 2*i;
            final int iH = iL + 1;
            if (parent[iL] > child[iL] || parent[iH] < child[iH]) {
                return false;
            }
        }
        return true;
    }

}
