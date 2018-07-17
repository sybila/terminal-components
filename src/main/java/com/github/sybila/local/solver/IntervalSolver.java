package com.github.sybila.local.solver;

import com.github.sybila.local.Solver;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Interval solver is the solver for a single real-valued parameter. It represents the
 * values as a set of intervals where each interval is always open on both ends (even after intersection, etc.).
 *
 * The intervals must be bounded (cannot use Double.XXX_INFINITY).
 *
 * The intervals are represented as an array of doubles, where each interval occupies two items.
 */
public final class IntervalSolver implements Solver<double[]> {

    /**
     * Working array is a cached thread-local array used for internal computations of the solver.
     * It should never be returned or otherwise passed around. It's state is generally assumed
     * to be unknown.
     */
    @NotNull
    private final ThreadLocal<double[]> workingArray = new ThreadLocal<>();

    @NotNull
    private final double[] fullSet;

    @NotNull
    private final double[] emptySet;

    public IntervalSolver(double low, double high) {
        fullSet = new double[] { low, high };
        emptySet = new double[0];
    }

    @NotNull
    @Override
    public double[] getFullSet() {
        return fullSet;
    }

    @NotNull
    @Override
    public double[] getEmptySet() {
        return emptySet;
    }

    @NotNull
    @Override
    public double[] intersect(@NotNull final double[] a, @NotNull final double[] b) {
        if (a.length == 0 || b.length == 0) return emptySet;
        // In the worst case, the new array will contain all thresholds from a and b.
        final double[] work = getWorkingArray(a.length + b.length);
        // Interval indices into each array. They shift in interval of 2, with iX + 1 being the closing bound.
        int iW = 0;
        int iA = 0;
        int iB = 0;
        // If iA or iB exceed the array, the remaining intersection is empty and we are done.
        while (iA < a.length && iB < b.length) {
            double aH = a[iA + 1];
            double bH = b[iB + 1];
            double rL = Math.max(a[iA], b[iB]);
            double rH = Math.min(aH, bH);
            if (rL < rH) {
                // intersection is valid, and we have to save it
                work[iW] = rL;
                work[iW + 1] = rH;
                iW += 2;
            }
            // In any case, we advance the interval with smallest upper bound (lower bound does not work!).
            if (aH > bH) {
                iB += 2;
            } else {
                iA += 2;
            }
        }
        if (iW == 0) return emptySet;
        return Arrays.copyOf(work, iW);
    }

    @NotNull
    @Override
    public double[] union(@NotNull final double[] a, @NotNull final double[] b) {
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        final double[] work = getWorkingArray(a.length + b.length);
        int iW = 0;
        int iA = 0;
        int iB = 0;
        // Variable wL contains the lower bound of currently constructed interval.
        // Once wL cannot be further extended, it is added to work.
        double wL = Math.min(a[0], b[0]);
        // Loop invariant: There is a continuous interval between wL and min(a[iA], b[iB]).
        while (iA < a.length && iB < b.length) {
            double aH = a[iA + 1];
            double bH = b[iB + 1];
            double rL = Math.max(a[iA], b[iB]);
            double rH = Math.min(aH, bH);
            if (rL > rH) {
                // There is no intersection. We have to end the interval using the smaller upper bound.
                // The bigger lower bound is the start of a new interval.
                work[iW] = wL;
                work[iW + 1] = rH;
                iW += 2;
                wL = rL;
            }
            // ELSE: The intervals intersect (even in a singular point). We can safely extend the result with
            // the smaller of the two intervals while preserving the invariant.

            // In any case, we advance the interval with smallest upper bound.
            if (aH > bH) {
                iB += 2;
            } else {
                iA += 2;
            }
        }
        double[] remaining  = iA < a.length ? a : b;
        int iR              = iA < a.length ? iA : iB;
        int toCopy          = remaining.length - iR;
        if (toCopy > 0) {
            System.arraycopy(remaining, iR, work, iW, toCopy);
            iW += toCopy;
        }
        // note that since both A and B are non-empty, iW > 0
        return Arrays.copyOf(work, iW);
    }

    @NotNull
    @Override
    public double[] complement(@NotNull final double[] x, @NotNull final double[] against) {
        if (x.length == 0) return against;
        if (against.length == 0) return emptySet;
        // Because we don't allow unbounded intervals, complement is just an intersection where x
        // has been extended with -inf and inf: [-inf, x1, x2, ... , xN, inf]
        double[] xExtended = new double[x.length + 2];
        xExtended[0] = Double.NEGATIVE_INFINITY;
        xExtended[xExtended.length - 1] = Double.POSITIVE_INFINITY;
        return intersect(x, against);
    }

    @Override
    public boolean encloses(@NotNull double[] x, @NotNull double[] subset) {
        if (subset.length == 0) return true;
        if (x.length == 0) return false;
        int iX = 0;
        int iS = 0;
        // Note that each interval in subset must be covered by exactly one interval in x. The is no way to
        // cover it by, say, two intervals, because such intervals would overlap.
        while (iX < x.length && iS < subset.length) {
            double xL = x[iX];
            double xH = x[iX + 1];
            double sL = subset[iS];
            double sH = subset[iS + 1];
            double rL = Math.max(xL, sL);
            double rH = Math.min(xH, sH);
            if (rL == sL && rH == sH) {
                // Interval is covered, continue to the next interval
                iS += 2;
            } else if (rL < rH) {
                // Interval intersects, but isn't covered. This interval cannot be covered by anything else,
                // because all other intervals in x are disjoint.
                return false;
            } else {
                // There is no intersection.
                if (sH <= xL) {
                    // Sub-interval is below current interval in x. Hence nothing in x covers it.
                    return false;
                } else {
                    // Sub-interval is above current interval in x. It can be covered by the next interval.
                    iX += 2;
                }
            }
        }
        // If whole subset is processed, the set is covered.
        return iS >= subset.length;
    }

    @Override
    public boolean isEmpty(@NotNull final double[] x) {
        return x.length == 0;
    }

    private double[] getWorkingArray(int size) {
        double[] current = workingArray.get();
        if (current == null || current.length < size) {
            current = new double[size];
            workingArray.set(current);
        }
        return current;
    }

}
