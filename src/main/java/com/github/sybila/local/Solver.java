package com.github.sybila.local;

import org.jetbrains.annotations.NotNull;

public interface Solver<T> {

    @NotNull
    T getFullSet();

    @NotNull
    T getEmptySet();

    @NotNull
    T intersect(@NotNull T a, @NotNull T b);

    @NotNull
    T union(@NotNull T a, @NotNull T b);

    @NotNull
    T complement(@NotNull T x, @NotNull T against);

}
