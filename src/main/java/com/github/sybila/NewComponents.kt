package com.github.sybila

import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors

class NewComponents : Algorithm {

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {

        val transitionSystem = DetOdeModel(model).asSingletonPartition()
        val counter = Count(transitionSystem)
        val magic = when (config.heuristics) {
            HeuristicType.NONE -> None(transitionSystem)
            HeuristicType.CARDINALITY -> Cardinality(transitionSystem)
            HeuristicType.CARDINALITY_STRUCTURE -> Magic(transitionSystem)
        }

        val universes = ArrayDeque<StateMap<Params>>()
        universes.push(TrueOperator(transitionSystem).compute())
        val executor = Executors.newSingleThreadExecutor()
        while (universes.isNotEmpty()) {

            val universe = universes.pop()
            SingletonChannel(ExplicitOdeModel(transitionSystem, universe, executor).asSingletonPartition()).run {

                val pivots = HashStateMap(ff)
                var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

                var pivotCount = 0
                do {
                    pivotCount += 1
                    val (pivot, params) = magic.findMagic(universe, uncovered)
                    pivots[pivot] = params
                    uncovered = uncovered and params.not()
                } while (uncovered.isSat())

                val F = FWD(pivots.asOp())
                val B = BWD(pivots.asOp())

                val F_minus_B = Complement(B, F).compute()

                if (F_minus_B.entries().asSequence().any { it.second.isSat() }) {
                    universes.push(F_minus_B)
                }

                val BB = BWD(F)

                val V_minus_BB = Complement(BB, universe.asOp()).compute()

                val newComponents = V_minus_BB.entries().asSequence().fold(ff) { a, b -> a or b.second }

                if (newComponents.isSat()) {
                    counter.push(newComponents)
                    universes.push(V_minus_BB)
                }
            }
        }
        executor.shutdown()


        return counter
    }

}

