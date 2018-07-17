package com.github.sybila.local;

import org.jetbrains.annotations.NotNull;

/**
 * Solver is a class which provides operations on a certain type of parameter set.
 * All operations are assumed to be thread safe! (i.e. any internal state of a solver is properly synchronised).
 *
 * @param <T> Parameter set type.
 */
public interface Solver<T> {

    @NotNull
    T getFullSet();

    @NotNull
    T getEmptySet();

    @NotNull
    T intersect(@NotNull final T a, @NotNull final T b);

    @NotNull
    T union(@NotNull final T a, @NotNull final T b);

    @NotNull
    T complement(@NotNull final T x, @NotNull final T against);

    boolean encloses(@NotNull final T x, @NotNull final T subset);

    boolean isEmpty(@NotNull final T x);

    double volume(@NotNull final T x);

    String print(@NotNull final T x);

}
