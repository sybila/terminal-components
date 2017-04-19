package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.huctl.Formula
import java.util.*
import java.util.concurrent.ExecutorService

class ExplicitOdeModel(
        source: Model<Params>,
        universe: StateMap<Params>,
        executor: ExecutorService
) : Model<Params>, Solver<Params> by source {

    override val stateCount: Int = source.stateCount

    private val successorCache = HashMap<Int, List<Transition<Params>>>(stateCount)
    private val pastSuccessorCache = HashMap<Int, List<Transition<Params>>>(stateCount)
    private val predecessorCache = HashMap<Int, List<Transition<Params>>>(stateCount)
    private val pastPredecessorCache = HashMap<Int, List<Transition<Params>>>(stateCount)

    init {
        source.run {
            fun intoUniverse(s: Int): ((Transition<Params>) -> Transition<Params>?) = { t ->
                val newBound = t.bound and universe[s] and universe[t.target]
                if (newBound.isSat()) t.copy(bound = newBound)
                else null
            }
            val r1 = executor.submit {
                for (s in universe.states()) {
                    successorCache[s] = s.successors(true).asSequence().map(intoUniverse(s)).toList().filterNotNull()
                }
            }
            val r2 = executor.submit {
                for (s in universe.states()) {
                    pastSuccessorCache[s] = s.successors(false).asSequence().map(intoUniverse(s)).toList().filterNotNull()
                }
            }
            val r3 = executor.submit {
                for (s in universe.states()) {
                    predecessorCache[s] = s.predecessors(true).asSequence().map(intoUniverse(s)).toList().filterNotNull()
                }
            }
            val r4 = executor.submit {
                for (s in universe.states()) {
                    pastPredecessorCache[s] = s.predecessors(false).asSequence().map(intoUniverse(s)).toList().filterNotNull()
                }
            }
            r1.get(); r2.get(); r3.get(); r4.get()
        }
    }

    override fun Formula.Atom.Float.eval(): StateMap<Params> = TODO("not implemented")

    override fun Formula.Atom.Transition.eval(): StateMap<Params> = TODO("not implemented")

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<Params>> {
        val cache = if (timeFlow) predecessorCache[this] else pastPredecessorCache[this]
        return cache?.iterator() ?: emptyList<Transition<Params>>().iterator()
    }

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<Params>> {
        val cache = if (timeFlow) successorCache[this] else pastSuccessorCache[this]
        return cache?.iterator() ?: emptyList<Transition<Params>>().iterator()
    }
}