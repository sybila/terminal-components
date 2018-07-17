package com.github.sybila.local

interface PivotChooser<S, T> {
    fun choose(universe: Map<S, T>): Map<S, T>
}

class NaivePivotChooser<S, T>(private val solver: Solver<T>) : PivotChooser<S, T> {
    override fun choose(universe: Map<S, T>): Map<S, T> {
        solver.run {
            var uncovered = universe.entries.fold(solver.emptySet) { a, b -> union(a, b.value) }
            val result = HashMap<S, T>()
            while (!isEmpty(uncovered)) {
                val (s, p) = universe.entries.first { !isEmpty(intersect(it.value, uncovered)) }
                result[s] = union(intersect(p, uncovered), result[s] ?: emptySet)
                uncovered = complement(p, uncovered)
            }
            return result
        }
    }
}

@Suppress("unused")
class VolumePivotChooser<S, T>(private val solver: Solver<T>) : PivotChooser<S, T> {
    override fun choose(universe: Map<S, T>): Map<S, T> {
        solver.run {    // add volume to scope
            var uncovered = universe.entries.fold(emptySet) { a, b -> union(a, b.value) }
            val result = HashMap<S, T>()
            while (!isEmpty(uncovered)) {
                val (s, p) = universe.entries.maxBy {
                    volume(intersect(it.value, uncovered))
                }!!
                result[s] = union(intersect(p, uncovered), result[s] ?: emptySet)
                uncovered = complement(p, uncovered)
            }
            return result
        }
    }
}

open class StructurePivotChooser<S, T>(protected val solver: Solver<T>, model: TransitionSystem<S, T>) : PivotChooser<S, T> {

    // parameter sets for different predecessor - successor differences
    // [0] - no difference, [1] - one more predecessor than successor, ...
    protected val degreeDifference: Map<S, List<T>> = run {
        val result = HashMap<S, List<T>>()

        fun MutableList<T>.setOrUnion(index: Int, value: T) {
            while (this.size <= index) add(solver.emptySet)
            this[index] = solver.union(this[index], value)
        }

        for (state in model.allStates.keys) {
            val successors = Count(solver)
            val predecessors = Count(solver)
            model.successors(state).forEach { t: S ->
                if (t != state) successors.push(model.edgeParams(state, t))
            }
            model.predecessors(state).forEach { t: S ->
                if (t != state) predecessors.push(model.edgeParams(t, state))
            }
            val list = ArrayList<T>()
            for (pI in (0 until predecessors.size)) {
                val p = predecessors[pI]
                for (sI in (0 until successors.size)) {
                    val s = successors[sI]
                    if (pI >= sI) {
                        val k = solver.intersect(p, s)
                        if (!solver.isEmpty(k)) {
                            list.setOrUnion(pI - sI, k)
                        }
                    }
                }
            }
            result[state] = list
        }

        result
    }

    override fun choose(universe: Map<S, T>): Map<S, T> {
        solver.run {
            var uncovered = universe.entries.fold(emptySet) { a, b -> union(a, b.value) }
            val result = HashMap<S, T>()
            while (!isEmpty(uncovered)) {
                val (s, p) = universe.entries.maxBy { (s, p) ->
                    degreeDifference[s]?.indexOfLast { !isEmpty(intersect(it, intersect(p, uncovered))) } ?: -1
                }!!
                val maxDiff = degreeDifference[s]?.last { !isEmpty(intersect(it, intersect(p, uncovered))) } ?: emptySet
                val takeWith = intersect(p, intersect(uncovered, maxDiff))
                result[s] = union(intersect(takeWith, uncovered), result[s] ?: emptySet)
                uncovered = complement(takeWith, uncovered)
            }
            return result
        }
    }

}

open class StructureAndCardinalityPivotChooser<S, T>(solver: Solver<T>, model: TransitionSystem<S, T>) : StructurePivotChooser<S, T>(solver, model) {

    override fun choose(universe: Map<S, T>): Map<S, T> {
        solver.run {
            var uncovered = universe.entries.fold(emptySet) { a, b -> union(a, b.value) }
            val result = HashMap<S, T>()
            while (!isEmpty(uncovered)) {
                val (s, p) = universe.entries.maxBy { (s, p) ->
                    (degreeDifference[s]?.indexOfLast { !isEmpty(intersect(it, intersect(p, uncovered))) } ?: -1)
                }!!
                val takeWith = intersect(p, uncovered)
                result[s] = union(intersect(takeWith, uncovered), result[s] ?: emptySet)
                uncovered = complement(takeWith, uncovered)
            }
            return result
        }
    }

}