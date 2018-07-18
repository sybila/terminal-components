package com.github.sybila.local.solver;

import com.github.sybila.local.Serializer;
import com.github.sybila.local.Solver;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Interval solver is the solver for a single real-valued parameter. It represents the
 * values as a set of intervals where each interval is always open on both ends (even after intersection, etc.).
 *
 * The intervals must be bounded (cannot use Double.XXX_INFINITY).
 *
 * The intervals are represented as an array of doubles, where each interval occupies two items.
 */
public final class IntervalSolver implements Solver<double[]>, Serializer<double[]> {

    public long opCount = 0;
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
        opCount += 1;
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
                work[iW] = rL; work[iW + 1] = rH;
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
        opCount += 1;
        if (a.length == 0) return b;
        if (b.length == 0) return a;
        final double[] work = getWorkingArray(a.length + b.length);
        int iW = 2;
        int iA = 0;
        int iB = 0;
        // Insert first lowest interval.
        if (a[0] < b[0]) {
            work[0] = a[0]; work[1] = a[1];
            iA += 2;
        } else {
            work[0] = b[0]; work[1] = b[1];
            iB += 2;
        }
        while (iA < a.length || iB < b.length) {
            double iL;
            double iH;
            // Choose A if iA is valid and B is either empty, or A has a lower interval.
            boolean chooseA = iA < a.length && (iB >= b.length || a[iA] < b[iB]);
            if (chooseA) {
                iL = a[iA]; iH = a[iA + 1];
                iA += 2;
            } else {
                iL = b[iB]; iH = b[iB + 1];
                iB += 2;
            }
            // Check if we can extend the previous inserted interval, if not, add new interval, otherwise, merge.
            if (iL <= work[iW - 1] && iH > work[iW - 1]) {
                work[iW - 1] = iH;
            }
            if (iL > work[iW - 1]) {
                work[iW] = iL; work[iW + 1] = iH;
                iW += 2;
            }
        }
        // note that since both A and B are non-empty, iW > 0
        return Arrays.copyOf(work, iW);
    }

    @NotNull
    @Override
    public double[] complement(@NotNull final double[] x, @NotNull final double[] against) {
        opCount += 1;
        if (x.length == 0) return against;
        if (against.length == 0) return emptySet;
        // Because we don't allow unbounded intervals, complement is just an intersection where x
        // has been extended with -inf and inf: [-inf, x1, x2, ... , xN, inf]
        double[] extended = new double[x.length + 2];
        extended[0] = Double.NEGATIVE_INFINITY;
        extended[extended.length - 1] = Double.POSITIVE_INFINITY;
        System.arraycopy(x, 0, extended, 1, x.length);
        return intersect(extended, against);
    }

    @Override
    public boolean encloses(@NotNull double[] x, @NotNull double[] subset) {
        opCount += 1;
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

    @Override
    public double volume(@NotNull double[] x) {
        if (x.length == 0) return 0.0;
        double result = 0.0;
        for (int i=0; i < x.length; i+=2) {
            result += x[i+1] - x[i];
        }
        return result;
    }

    @Override
    public String print(@NotNull double[] x) {
        return Arrays.toString(x);
    }

    @Override
    public void write(@NotNull DataOutputStream stream, @NotNull double[] x) throws IOException {
        stream.writeInt(x.length);
        for (double d : x) {
            stream.writeDouble(d);
        }
    }

    @NotNull
    @Override
    public double[] read(@NotNull DataInputStream stream) throws IOException {
        double[] result = new double[stream.readInt()];
        for (int i=0; i<result.length; i++) {
            result[i] = stream.readDouble();
        }
        return result;
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
