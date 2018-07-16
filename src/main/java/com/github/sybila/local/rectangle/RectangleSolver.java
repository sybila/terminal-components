package com.github.sybila.local.rectangle;

import com.github.sybila.ode.model.OdeModel;
import kotlin.Pair;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * The rectangle set is simply an array of rectangles. There is a way more efficient way to do this
 * (interval decision diagrams), but let's not get into that just now.
 *
 * The main rule is that the rectangles do not overlap!
 *
 * Not that contrary to Rectangle, rectangle set can never by null. If you want to express an empty
 * rectangle set, use the static constant.
 */
public final class RectangleSolver {

    @NotNull
    static final double[][] EMPTY_SET = new double[0][];

    // This value is initialised after the model is loaded, but before any computations and should be
    // thus also considered final!
    @NotNull
    static double[][] FULL_SET = new double[1][0];

    private static final ThreadLocal<double[][]> workArray = new ThreadLocal<>();

    static void initFromModel(OdeModel model) {
        int dim = model.getParameters().size();
        FULL_SET = new double[1][dim];
        for (int i=0; i<dim; i++) {
            Pair<Double, Double> range = model.getParameters().get(i).getRange();
            FULL_SET[0][2*i] = range.getFirst();
            FULL_SET[0][2*i+1] = range.getSecond();
        }
    }

    /**
     * Obtain an array of undefined rectangles of given count.
     * This array is immediately cached, so you don't have to return it.
     */
    private static double[][] getWorkArray(int items) {
        double[][] array = workArray.get();
        if (array == null || array.length < items) {
            array = new double[items][];
            workArray.set(array);
        }
        return array;
    }

    @NotNull
    static double[][] intersect(@NotNull double[][] a, @NotNull double[][] b) {
        if (a.length == 0) return a;
        if (b.length == 0) return b;
        // just reference equality for fast fallback
        if (a == FULL_SET) return b;
        if (b == FULL_SET) return a;
        final double[][] workArray = getWorkArray(a.length * b.length);
        int workIndex = 0;
        for (double[] rA : a) {
            for (double[] rB : b) {
                double[] intersection = Rectangle.intersect(rA, rB);
                if (intersection != null) {
                    workArray[workIndex] = intersection;
                    workIndex += 1;
                }
            }
        }
        return Arrays.copyOf(workArray, workIndex);
    }

    /*@NotNull
    static double[][] union(@NotNull double[][] a, @NotNull double[][] b) {

    }*/


    /**
     * Subtract two rectangles. The subtraction can produce up-to 2*|params| smaller rectangles.
     */
    /*@NotNull
    static double[][] subtract(@Nullable double[] keep, @Nullable double[] remove) {
        if (keep == null) return EMPTY_SET;
        double[] intersection = Rectangle.intersect(keep, remove);
        if (remove == null || intersection == null) {
            double[][] result = new double[1][];
            result[0] = keep;
            return result;
        }
        final int dim = keep.length / 2;
        final double[][] workArray = getWorkArray(2 * dim);
        // go through each dimension and try to compute
    }*/

}
