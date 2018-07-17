package com.github.sybila.local;

import kotlin.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class ExplicitTransitionSystem<S, T> implements TransitionSystem<S, T> {

    @NotNull
    private final Solver<T> solver;

    @NotNull
    private final Map<S, T> states;

    @NotNull
    private final Map<Pair<S, S>, T> edges;

    @NotNull
    private final Map<S, List<S>> successors;

    @NotNull
    private final Map<S, List<S>> predecessors;


    public ExplicitTransitionSystem(
            @NotNull Solver<T> solver,
            @NotNull Map<S, T> states,
            @NotNull Map<Pair<S, S>, T> edges,
            @NotNull Map<S, List<S>> successors,
            @NotNull Map<S, List<S>> predecessors) {
        this.solver = solver;
        this.states = states;
        this.edges = edges;
        this.successors = successors;
        this.predecessors = predecessors;
    }

    @NotNull
    @Override
    public Map<S, T> getAllStates() {
        return states;
    }

    @NotNull
    @Override
    public List<S> successors(@NotNull S source) {
        List<S> result = successors.get(source);
        if (result == null) return Collections.emptyList();
        return result;
    }

    @NotNull
    @Override
    public List<S> predecessors(@NotNull S source) {
        List<S> result = predecessors.get(source);
        if (result == null) return Collections.emptyList();
        return result;
    }

    @NotNull
    @Override
    public T edgeParams(@NotNull S from, @NotNull S to) {
        T params = edges.get(new Pair<>(from, to));
        if (params == null) return solver.getEmptySet();
        return params;
    }

    @NotNull
    @Override
    public TransitionSystem<S, T> restrictTo(@NotNull final Map<S, T> universe) {
        Map<Pair<S, S>, T> newEdges = new HashMap<>();
        for (Map.Entry<Pair<S, S>, T> edge : edges.entrySet()) {
            T newFromParams = universe.get(edge.getKey().getFirst());
            T newToParams = universe.get(edge.getKey().getSecond());
            T oldEdgeParams = edge.getValue();
            if (newFromParams != null && newToParams != null && oldEdgeParams != null) {
                T newEdgeParams = solver.intersect(oldEdgeParams, solver.intersect(newFromParams, newToParams));
                if (!solver.isEmpty(newEdgeParams)) {
                    newEdges.put(edge.getKey(), newEdgeParams);
                }
            }
        }
        Map<S, List<S>> newSuccessors = restrictTransitions(successors, newEdges, false);
        Map<S, List<S>> newPredecessors = restrictTransitions(predecessors, newEdges, true);

        return new ExplicitTransitionSystem<>(solver, universe, newEdges,
                newSuccessors, newPredecessors);
    }

    private static <S, T> Map<S, List<S>> restrictTransitions(Map<S, List<S>> values, Map<Pair<S, S>, T> edges, boolean flip) {
        Map<S, List<S>> newValues = new HashMap<>(values.size());
        for (Map.Entry<S, List<S>> entry : values.entrySet()) {
            List<S> updated = new ArrayList<>(entry.getValue().size());
            for (S target : entry.getValue()) {
                Pair<S, S> key;
                if (flip) {
                    key = new Pair<>(target, entry.getKey());
                } else {
                    key = new Pair<>(entry.getKey(), target);
                }
                if (edges.containsKey(key)) {
                    updated.add(target);
                }
            }
            if (!updated.isEmpty()) {
                newValues.put(entry.getKey(), updated);
            }
        }
        return newValues;
    }
}
