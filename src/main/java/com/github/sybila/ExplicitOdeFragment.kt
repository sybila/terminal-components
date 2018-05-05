package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.rect.Rectangle

/**
 * A simple utility class for keeping explicit thread safe representation of the state space
 * with the ability to quickly restrict the state space to a specific state map.
 */
abstract class ExplicitOdeFragment<T: Any, S: ExplicitOdeFragment<T, S>>(
        protected val solver: Solver<T>,
        final override val stateCount: Int,
        protected val universe: StateMap<T>,
        protected val successors: Array<List<Transition<T>>>,
        protected val predecessors: Array<List<Transition<T>>>
) : Model<T>, Solver<T> by solver, IntervalSolver<T> {

    override fun Formula.Atom.Float.eval(): StateMap<T> = error("This type of model does not have atoms.")

    override fun Formula.Atom.Transition.eval(): StateMap<T> = error("This type of model does not have atoms.")

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<T>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<T>> {
        val map = if (timeFlow) successors else predecessors
        return map[this].iterator()
    }

    protected fun Array<List<Transition<T>>>.restrictTo(universe: StateMap<T>): Array<List<Transition<T>>> = Array(stateCount) { s ->
        this[s].mapNotNull { t ->
            val newBound = t.bound and universe[s] and universe[t.target]
            newBound.takeIf { it.isSat() }?.let { Transition(t.target, t.direction, it) }
        }
    }

    override fun T.asIntervals(): Array<Array<DoubleArray>> {
        if (solver !is IntervalSolver<*>) error("Invalid solver! Requires IntervalSolver.")
        @Suppress("UNCHECKED_CAST")
        (solver as IntervalSolver<T>).run {
            return this@asIntervals.asIntervals()
        }
    }

    private val naivePivotChooser = object : PivotChooser<T> {
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

    private val volumePivotChooser = object : PivotChooser<T> {
        override fun choose(universe: StateMap<T>): StateMap<T> {
            var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
            val result = HashStateMap(ff, emptyMap())
            while (uncovered.isSat()) {
                val (s, p) = universe.entries().asSequence().maxBy {
                    //val x = it.second as MutableSet<Rectangle>
                    (it.second and uncovered).volume() /// x.size
                }!!
                result.setOrUnion(s, p and uncovered)
                uncovered = uncovered and p.not()
            }
            return result
        }
    }

    private val structurePivotChooser = object : PivotChooser<T> {

        // parameter sets for different predecessor - successor differences
        // [0] - no difference, [1] - one more predecessor than successor, ...
        private val degreeDifference = Array<List<T>>(stateCount) { state ->
            fun MutableList<T>.setOrUnion(index: Int, value: T) {
                while (this.size <= index) add(ff)
                this[index] = this[index] or value
            }
            val successors = Count(solver)
            state.successors(true).forEach { t ->
                if (t.target != state) {
                    successors.push(t.bound)
                }
            }
            state.successors(true).asSequence().fold(Count(solver)) { count, t ->
                if (t.target != state) {
                    count.push(t.bound)
                }
                count
            }
            val predecessors = state.predecessors(true).asSequence().fold(Count(solver)) { count, t ->
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

        override fun choose(universe: StateMap<T>): StateMap<T> {
            var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }
            val result = HashStateMap(ff, emptyMap())
            while (uncovered.isSat()) {
                val (s, p) = universe.entries().asSequence().maxBy { (s, p) ->
                    degreeDifference[s].indexOfLast { (it and p and uncovered).isSat() }
                }!!
                val takeWith = p and uncovered //and degreeDifference[s].last { (it and p and uncovered).isSat() }
                result.setOrUnion(s, takeWith)
                uncovered = uncovered and takeWith.not()
            }
            return result
        }
    }

    internal val pivot: PivotChooser<T> = structurePivotChooser

    abstract fun restrictTo(universe: StateMap<T>): S
    abstract fun T.volume(): Double

    /*class Interval(
            solver: Solver<IntervalSet>,
            stateCount: Int,
            successors: Array<List<Transition<IntervalSet>>>,
            predecessors: Array<List<Transition<IntervalSet>>>
    ) : ExplicitOdeFragment<IntervalSet, ExplicitOdeFragment.Interval>(solver, stateCount, successors, predecessors) {

        override fun restrictTo(universe: StateMap<IntervalSet>) = ExplicitOdeFragment.Interval(
                solver, stateCount,
                successors = successors.restrictTo(universe),
                predecessors = predecessors.restrictTo(universe)
        )

        override fun IntervalSet.volume(): Double {
            var r = 0.0
            values.stream().forEach { i ->
                r += thresholds[i+1] - thresholds[i]
            }
            return r
        }

    }

    class Grid(
            solver: Solver<RectangleSet>,
            stateCount: Int,
            successors: Array<List<Transition<RectangleSet>>>,
            predecessors: Array<List<Transition<RectangleSet>>>
    ) : ExplicitOdeFragment<RectangleSet, ExplicitOdeFragment.Grid>(solver, stateCount, successors, predecessors) {

        override fun restrictTo(universe: StateMap<RectangleSet>): Grid = ExplicitOdeFragment.Grid(
                solver, stateCount,
                successors = successors.restrictTo(universe),
                predecessors = predecessors.restrictTo(universe)
        )

        override fun RectangleSet.volume(): Double {
            val modifier = thresholdsX.size - 1
            var r = 0.0
            values.stream().forEach { i ->
                val x = i % modifier
                val y = i / modifier
                r += (thresholdsX[x+1] - thresholdsX[x]) * (thresholdsY[y+1] - thresholdsY[y])
            }
            return r
        }
    }*/

    class Rectangular(
            solver: Solver<MutableSet<Rectangle>>,
            stateCount: Int,
            universe: StateMap<MutableSet<Rectangle>>,
            successors: Array<List<Transition<MutableSet<Rectangle>>>>,
            predecessors: Array<List<Transition<MutableSet<Rectangle>>>>
    ) : ExplicitOdeFragment<MutableSet<Rectangle>, Rectangular>(solver, stateCount, universe, successors, predecessors) {

        override fun restrictTo(universe: StateMap<MutableSet<Rectangle>>): Rectangular = Rectangular(
                solver, stateCount, universe,
                successors = successors.restrictTo(universe),
                predecessors = predecessors.restrictTo(universe)
        )

        override fun MutableSet<Rectangle>.volume(): Double {
            return this.fold(0.0) { a, b ->
                a + b.volume()
            }
        }
    }

}