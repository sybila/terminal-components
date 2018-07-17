package com.github.sybila.local;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class Algorithm<S, T> {

    @NotNull
    private final Solver<T> solver;

    @NotNull
    private final Count<T> count;

    @NotNull
    private final ComponentStore<S, T> store;

    @NotNull
    private final PivotChooser<S, T> pivotChooser;

    @NotNull
    private final TransitionSystem<S, T> model;


    public Algorithm(@NotNull Solver<T> solver, @NotNull TransitionSystem<S, T> model) {
        this.solver = solver;
        this.model = model;
        this.count = new Count<>(solver);
        this.store = new ComponentStore<>(solver);
        this.pivotChooser = new NaivePivotChooser<>(solver);
    }

    public List<Map<S, T>> execute() {
        iteration(model, model.getAllStates());
        return store.getComponentMapping(count);
    }

    private void iteration(final TransitionSystem<S, T> ts, final Map<S, T> universe) {
        System.out.println("Start iteration with universe: "+universe.size());
        Map<S, T> pivot = pivotChooser.choose(universe);
        System.out.println("Pivot chosen: "+pivot.size());

        T universeParams = allParams(universe);

        Map<S, T> F = reachForward(ts, pivot);
        Map<S, T> B = reachBackward(ts, pivot, F);
        Map<S, T> F_minus_B = complement(B, F);

        // The set of parameters where we haven't discovered a component.
        T continueWith = allParams(F_minus_B);
        if (!solver.isEmpty(continueWith)) {
            startIteration(ts.restrictTo(F_minus_B), F_minus_B);
        }

        T componentFound = solver.complement(continueWith, universeParams);
        if (!solver.isEmpty(componentFound)) {
            store.push(F, componentFound);
        }

        Map<S, T> BB = reachBackward(ts, F, null);
        Map<S, T> V_minus_BB = complement(BB, universe);

        T newComponents = allParams(V_minus_BB);
        if (!solver.isEmpty(newComponents)) {
            count.push(newComponents);
            startIteration(ts.restrictTo(V_minus_BB), V_minus_BB);
        }
    }

    private T allParams(Map<S, T> map) {
        T params = solver.getEmptySet();
        for (Map.Entry<S, T> entry : map.entrySet()) {
            params = solver.union(params, entry.getValue());
        }
        return params;
    }

    private void startIteration(TransitionSystem<S, T> ts, Map<S, T> universe) {
        iteration(ts, universe);
    }

    private Map<S, T> complement(final Map<S, T> x, final Map<S, T> against) {
        final Map<S, T> result = new HashMap<>();
        for (S s : against.keySet()) {
            T all = against.get(s);
            T minus = x.get(s);
            if (minus == null) {
                result.put(s, all);
            } else {
                T r = solver.complement(minus, all);
                if (!solver.isEmpty(r)) {
                    result.put(s, r);
                }
            }
        }
        return result;
    }

    private Map<S, T> reachForward(final TransitionSystem<S, T> ts, final Map<S, T> initial) {
        final Map<S, T> result = new HashMap<>(initial.size());
        Set<S> recompute = initReachResult(result, initial);
        while (!recompute.isEmpty()) {
            Set<S> recomputeNext = new HashSet<>(recompute.size());
            for (S s : recompute) {
                for (S t : ts.successors(s)) {
                    T edge = ts.edgeParams(s, t);
                    T push = solver.intersect(result.get(s), edge);
                    if (setOrUnion(solver, result, t, push)) {
                        recomputeNext.add(t);
                    }
                }
            }
            recompute = recomputeNext;
        }
        return result;
    }

    private Map<S, T> reachBackward(
            @NotNull final TransitionSystem<S, T> ts,
            @NotNull final Map<S, T> initial,
            @Nullable final Map<S, T> path
    ) {
        final Map<S, T> result = new HashMap<>(initial.size());
        Set<S> recompute = initReachResult(result, initial);
        while (!recompute.isEmpty()) {
            Set<S> recomputeNext = new HashSet<>(recompute.size());
            for (S s : recompute) {
                for (S t : ts.predecessors(s)) {
                    if (path != null && path.get(t) == null) continue;
                    T edge = ts.edgeParams(t, s);
                    T push = solver.intersect(result.get(s), edge);
                    if (path != null) {
                        T max = path.get(t);
                        push = solver.intersect(push, max);
                    }
                    if (setOrUnion(solver, result, t, push)) {
                        recomputeNext.add(t);
                    }
                }
            }
            recompute = recomputeNext;
        }
        return result;
    }

    private static <S, T> boolean setOrUnion(Solver<T> solver, final Map<S, T> result, S state, T value) {
        if (value == null) return false;
        if (solver.isEmpty(value)) return false;
        T current = result.get(state);
        if (current == null) {
            result.put(state, value);
            return true;
        } else if (!solver.encloses(current, value)) {
            result.put(state, solver.union(current, value));
            return true;
        }
        return false;
    }

    private static <S, T> Set<S> initReachResult(final Map<S, T> result, final Map<S, T> initial) {
        Set<S> recompute = new HashSet<>(initial.size());
        for (S s : initial.keySet()) {
            T i = initial.get(s);
            if (i != null) {
                result.put(s, i);
                recompute.add(s);
            }
        }
        return recompute;
    }

}
