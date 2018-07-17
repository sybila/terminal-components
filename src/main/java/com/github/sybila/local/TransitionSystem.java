package com.github.sybila.local;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public interface TransitionSystem<S, T> {

    /**
     * Return the number of states in this system.
     */
    @NotNull
    Map<S, T> getAllStates();

    @NotNull
    List<S> successors(@NotNull S source);

    @NotNull
    List<S> predecessors(@NotNull S source);

    @NotNull
    T edgeParams(@NotNull S from, @NotNull S to);

    @NotNull
    TransitionSystem<S, T> restrictTo(@NotNull Map<S, T> universe);

}
