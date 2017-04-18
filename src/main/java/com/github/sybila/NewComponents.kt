package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis

class NewComponents : Algorithm {

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {

        var mcTime = 0L
        var maxMcTime = 0L
        var mcCount = 0

        val transitionSystem = DetOdeModel(model).asSingletonPartition()
        val counter = Count(transitionSystem)
        val magic = Heuristics(transitionSystem)

        val universes = ArrayDeque<StateMap<Params>>()
        universes.push(TrueOperator(transitionSystem).compute())
        val executor = Executors.newSingleThreadExecutor()
        while (universes.isNotEmpty()) {

            val universe = universes.pop()
            SingletonChannel(ExplicitOdeModel(transitionSystem, universe, executor).asSingletonPartition()).run {

                /*val pivots = HashStateMap(ff)
                var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

                var pivotCount = 0
                do {
                    pivotCount += 1
                    val pivot = magic.findMagic(universe, uncovered)
                    pivots[pivot.state] = pivot.params
                    uncovered = uncovered and pivot.params.not()
                } while (uncovered.isSat())
                */
                val pivots = magic.findMagic(universe)
                
                val F = FWD(pivots.asOp())
                val B = BWD(pivots.asOp())

                val time1 = measureTimeMillis {
                    val F_minus_B = Complement(B, F).compute()

                    if (F_minus_B.entries().asSequence().any { it.second.isSat() }) {
                        universes.push(F_minus_B)
                    }
                }
                mcCount += 1
                mcTime += time1
                if (time1 > maxMcTime) maxMcTime = time1

                val BB = BWD(F)

                val time2 = measureTimeMillis {
                    val V_minus_BB = Complement(BB, universe.asOp()).compute()

                    val newComponents = V_minus_BB.entries().asSequence().fold(ff) { a, b -> a or b.second }

                    if (newComponents.isSat()) {
                        counter.push(newComponents)
                        universes.push(V_minus_BB)
                    }
                }
                mcCount += 1
                mcTime += time2
                if (time2 > maxMcTime) maxMcTime = time2
            }
        }

        println("Max: $maxMcTime, total: $mcTime, avr: ${mcTime/mcCount}")

        return counter
    }

}

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