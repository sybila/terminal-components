package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.IntervalSolver
import com.github.sybila.ode.generator.rect.Rectangle

/**
 * A simple utility class for keeping explicit thread safe representation of the state space
 * with the ability to quickly restrict the state space to a specific state map.
 */
class ExplicitOdeFragment<T: Any>(
        private val solver: Solver<T>,
        override val stateCount: Int,
        private val pivotFactory: (ExplicitOdeFragment<T>) -> PivotChooser<T>,
        private val successors: Array<List<Transition<T>>>,
        private val predecessors: Array<List<Transition<T>>>
) : Model<T>, Solver<T> by solver, IntervalSolver<T> {

    val pivot: PivotChooser<T> = pivotFactory(this)

    override fun Formula.Atom.Float.eval(): StateMap<T> = error("This type of model does not have atoms.")

    override fun Formula.Atom.Transition.eval(): StateMap<T> = error("This type of model does not have atoms.")

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<T>> = this.successors(!timeFlow)

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<T>> {
        val map = if (timeFlow) successors else predecessors
        return map[this].iterator()
    }

    private fun Array<List<Transition<T>>>.restrictTo(universe: StateMap<T>): Array<List<Transition<T>>> = Array(stateCount) { s ->
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

    fun restrictTo(universe: StateMap<T>): ExplicitOdeFragment<T> = ExplicitOdeFragment(
            solver, stateCount, pivotFactory,
            successors = successors.restrictTo(universe),
            predecessors = predecessors.restrictTo(universe)
    )

    fun T.volume(): Double {
        @Suppress("UNCHECKED_CAST")
        val rect = this as MutableSet<Rectangle>
        return rect.fold(0.0) { a, b ->
            a + b.volume()
        }
    }


}