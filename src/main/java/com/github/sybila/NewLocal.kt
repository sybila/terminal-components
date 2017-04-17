package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

class NewLocal(parallelism: Int) : Algorithm {

    val universeExecutor = Executors.newFixedThreadPool(parallelism)!!
    private val pending = ArrayDeque<Future<Unit>>()

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {

        val transitionSystem = DetOdeModel(model).asSingletonPartition()
        val counter = Count(transitionSystem)
        val magic = Heuristics(transitionSystem)

        val allStates = transitionSystem.run { TrueOperator(this).compute() }

        var wait: Future<Unit>? = universeExecutor.submit(UniverseAction(counter, magic, transitionSystem, allStates))

        do {
            wait?.get()
            synchronized(pending) {
                wait = pending.poll()
            }
        } while (wait != null)

        universeExecutor.shutdown()

        return counter
    }

    private inner class UniverseAction(
            private val counter: Count<Params>,
            private val magic: Heuristics,
            private val transitionSystem: Model<Params>,
            private val universe: StateMap<Params>): Callable<Unit> {

        override fun call() {
            SingletonChannel(ExplicitOdeModel(transitionSystem, universe, universeExecutor).asSingletonPartition()).run {

                val pivots = HashStateMap(ff)
                var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

                var pivotCount = 0
                do {
                    pivotCount += 1
                    val pivot = magic.findMagic(universe, uncovered)
                    pivots[pivot.state] = pivot.params
                    uncovered = uncovered and pivot.params.not()
                } while (uncovered.isSat())

                println("Pivots: $pivotCount")

                val F = FWD(pivots.asOp())
                val B = BWD(pivots.asOp())

                val F_minus_B = Complement(B, F).compute()

                if (F_minus_B.entries().asSequence().any { it.second.isSat() }) {
                    val next = universeExecutor.submit(UniverseAction(
                            counter, magic, this, F_minus_B
                    ))
                    synchronized(pending) {
                        pending.add(next)
                    }
                }

                val BB = BWD(F)

                val V_minus_BB = Complement(BB, universe.asOp()).compute()

                val newComponents = V_minus_BB.entries().asSequence().fold(ff) { a, b -> a or b.second }

                if (newComponents.isSat()) {
                    counter.push(newComponents)
                    val next = universeExecutor.submit(UniverseAction(
                            counter, magic, this, V_minus_BB
                    ))
                    synchronized(pending) {
                        pending.add(next)
                    }
                }
            }
        }

    }

}