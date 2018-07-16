package com.github.sybila.local.interval;

import com.github.sybila.local.Solver;
import org.jetbrains.annotations.NotNull;

/**
 * Interval solver is the simplest type of solver
 */
public class IntervalSolver implements Solver<double[]> {

    @NotNull
    @Override
    public double[] getFullSet() {
        return new double[0];
    }

    @NotNull
    @Override
    public double[] getEmptySet() {
        return new double[0];
    }

    @NotNull
    @Override
    public double[] intersect(@NotNull double[] a, @NotNull double[] b) {
        return new double[0];
    }

    @NotNull
    @Override
    public double[] union(@NotNull double[] a, @NotNull double[] b) {
        return new double[0];
    }

    @NotNull
    @Override
    public double[] complement(@NotNull double[] x, @NotNull double[] against) {
        return new double[0];
    }
}
