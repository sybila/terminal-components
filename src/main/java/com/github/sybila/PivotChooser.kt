package com.github.sybila

import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.mutable.HashStateMap

interface PivotChooser<T: Any> {
    fun choose(universe: StateMap<T>): StateMap<T>
}

class NaivePivotChooser<T: Any>(solver: Solver<T>) : PivotChooser<T>, Solver<T> by solver {
    override fun choose(universe: StateMap<T>): StateMap<T> {
        var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
        val result = HashStateMap(ff, emptyMap())
        while (uncovered.isSat()) {
            val (s, p) = universe.entries().asSequence().first { (it.second and uncovered).isSat() }
            result.setOrUnion(s, p and uncovered)
            uncovered = uncovered and p.not()
        }
        return result
    }
}

@Suppress("unused")
class VolumePivotChooser<T: Any>(private val solver: ExplicitOdeFragment<T>) : PivotChooser<T>, Solver<T> by solver {
    override fun choose(universe: StateMap<T>): StateMap<T> {
        solver.run {    // add volume to scope
            var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
            val result = HashStateMap(ff, emptyMap())
            while (uncovered.isSat()) {
                val (s, p) = universe.entries().asSequence().maxBy {
                    (it.second and uncovered).volume() /// x.size
                }!!
                result.setOrUnion(s, p and uncovered)
                uncovered = uncovered and p.not()
            }
            return result
        }
    }
}

open class StructurePivotChooser<T: Any>(model: ExplicitOdeFragment<T>) : PivotChooser<T>, Solver<T> by model {

        // parameter sets for different predecessor - successor differences
        // [0] - no difference, [1] - one more predecessor than successor, ...
        protected val degreeDifference = model.run {
            Array<List<T>>(stateCount) { state ->
                fun MutableList<T>.setOrUnion(index: Int, value: T) {
                    while (this.size <= index) add(ff)
                    this[index] = this[index] or value
                }
                val successors = Count(model)
                state.successors(true).forEach { t ->
                    if (t.target != state) {
                        successors.push(t.bound)
                    }
                }
                state.successors(true).asSequence().fold(Count(model)) { count, t ->
                    if (t.target != state) {
                        count.push(t.bound)
                    }
                    count
                }
                val predecessors = state.predecessors(true).asSequence().fold(Count(model)) { count, t ->
                    if (t.target != state) {
                        count.push(t.bound)
                    }
                    count
                }
                val result = ArrayList<T>()
                for (pI in (0 until predecessors.size)) {
                    val p = predecessors[pI]
                    for (sI in (0 until successors.size)) {
                        val s = successors[sI]
                        if (pI >= sI) {
                            val k = p and s
                            if (k.isSat()) {
                                result.setOrUnion(pI - sI, k)
                            }
                        }
                    }
                }
                result
            }
        }

        override fun choose(universe: StateMap<T>): StateMap<T> {
            var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
            val result = HashStateMap(ff, emptyMap())
            while (uncovered.isSat()) {
                val (s, p) = universe.entries().asSequence().maxBy { (s, p) ->
                    degreeDifference[s].indexOfLast { (it and p and uncovered).isSat() }
                }!!
                val takeWith = p and uncovered and degreeDifference[s].last { (it and p and uncovered).isSat() }
                result.setOrUnion(s, takeWith)
                uncovered = uncovered and takeWith.not()
            }
            return result.also { println("pivot size: ${it.sizeHint}, universe size: ${universe.sizeHint}") }
        }

}

open class StructureAndCardinalityPivotChooser<T: Any>(private val model: ExplicitOdeFragment<T>) : StructurePivotChooser<T>(model) {

    override fun choose(universe: StateMap<T>): StateMap<T> = model.run {
        val start = System.currentTimeMillis()
        var uncovered = universe.entries().asSequence().fold(ff) { a, b -> (a or b.second) }
        val result = HashStateMap(ff, emptyMap())
        while (uncovered.isSat()) {
            var max: Pair<Int, T>? = null
            var maxIndex = -1
            //var maxVolume = 0.0
            for ((s, p) in universe.entries()/*.sortedByDescending { degreeDifference[it.key]?.size ?: 0 }*/) {
                val degrees = degreeDifference[s]
                if (degrees.lastIndex <= maxIndex) continue
                val canCover = (p and uncovered)
                for (i in degrees.indices.reversed()) {
                    if (i <= maxIndex) break // if we consider worse item than current max, just skip
                    val degreeParams = (degrees[i] and canCover)
                    if (degreeParams.isSat()/* && degreeParams.volume() >= maxVolume*/) {
                        max = s to canCover
                        maxIndex = i
                        //maxVolume = degreeParams.volume()
                    }
                }
            }
            val (s, canCover) = max!!
            result[s] = (canCover or result[s])
            uncovered = (uncovered and canCover.not())
        }
        return result.also { println("Pivot choose time: ${System.currentTimeMillis() - start}, pivot size: ${it.sizeHint}, universe size: ${universe.sizeHint}") }
        /*var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
        val result = HashStateMap(ff, emptyMap())
        while (uncovered.isSat()) {
            val (s, p) = universe.entries().asSequence().maxBy { (s, p) ->
                degreeDifference[s].indexOfLast { (it and p and uncovered).isSat() }
            }!!
            val takeWith = p and uncovered
            result.setOrUnion(s, takeWith)
            uncovered = uncovered and takeWith.not()
        }
        return result*/
    }

}