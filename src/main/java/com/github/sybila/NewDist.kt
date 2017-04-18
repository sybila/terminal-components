package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Partition
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.assuming
import com.github.sybila.checker.map.mutable.ContinuousStateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors

class NewDist(
        parallelism: Int
) : Algorithm {

    private val universeExecutor = Executors.newFixedThreadPool(parallelism)

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {

        val transitionSystem = DetOdeModel(model).asSingletonPartition()
        val counter = Count(transitionSystem)
        val magic = Heuristics(transitionSystem)

        val allStates = transitionSystem.run { TrueOperator(this).compute() }

        val universeQueue = ArrayDeque<StateMap<Params>>()
        universeQueue.push(allStates)

        var start = 0L
        var mcTime = 0L
        var mcCount = 0L
        var pivotTime = 0L
        var initTime = 0L

        //println("State count: ${transitionSystem.stateCount}")

        while (universeQueue.isNotEmpty()) {
            val universe = universeQueue.poll()

            // restrict state space to this universe
            start = System.currentTimeMillis()
            val states = universe.states().asSequence().toList().toIntArray()
            states.sort()
            val restrictedSystem = ExplicitOdeModel(transitionSystem, universe, universeExecutor)

            val parallelism = Math.min(config.parallelism, Math.max(1, states.size / 1000))

            // compute universe partitioning
            val partitions = (0 until parallelism).map {
                //BlockPartition(it, config.parallelism, 100, restrictedSystem)
                //AdaptivePartition(it, config.parallelism, states, restrictedSystem)
                NewBlockPartition(it, parallelism, config.blocksPerDimension, model, restrictedSystem)
            }.connectWithNoCopy()//.connectWithSharedMemory()
            initTime += (System.currentTimeMillis() - start)

            start = System.currentTimeMillis()
            // find pivots
            var pivotCount = 0
            val pivots = transitionSystem.run {
                val pivots = HashStateMap(ff)
                var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

                do {
                    pivotCount += 1
                    val pivot = magic.findMagic(universe, uncovered)
                    pivots[pivot.state] = pivot.params
                    uncovered = uncovered and pivot.params.not()
                } while (uncovered.isSat())

                pivots
            }
            pivotTime += (System.currentTimeMillis() - start)

            //println("Pivots: $pivotCount")

            val pivotOp = partitions.map { it.run {
                pivots.restrictToPartition().asOp()
            } }

            val universeOp = partitions.map { it.run {
                universe.restrictToPartition().asOp()
            } }

            val F = pivotOp.zipRun(partitions) { FWD(it) }

            val B = pivotOp.zipRun(partitions) { BWD(it) }

            val F_minus_B = B.zip(F).zipRun(partitions) { (B, F) ->
                Complement(B, F)
            }

            start = System.currentTimeMillis()
            F_minus_B
                    // compute in parallel
                    .map { universeExecutor.submit<StateMap<Params>> { it.compute() } }.map { it.get() }
                    .also {
                        val duration = (System.currentTimeMillis() - start)
                        if (duration > 1000) mcCount += duration
                        mcTime += duration
                    }
                    // check for emptiness
                    .assuming {
                        transitionSystem.run {
                            it.any { it.entries().asSequence().any { it.second.isSat() } }
                        }
                    }?.let { result ->
                val union = ContinuousStateMap(0, transitionSystem.stateCount, transitionSystem.ff).apply {
                    for (map in result) {
                        var count = 0
                        for ((s, p) in map.entries()) {
                            this[s] = p
                            count += 1
                        }
                        //print("$count, ")
                    }
                    //println()
                }
                universeQueue.add(union)
            }

            val BB = F.zipRun(partitions) { BWD(it) }

            val V_minus_BB = BB.zip(universeOp).zipRun(partitions) { (BB, universe) ->
                Complement(BB, universe)
            }

            start = System.currentTimeMillis()
            V_minus_BB
                    // compute in parallel
                    .map { universeExecutor.submit<StateMap<Params>> { it.compute() } }.map { it.get() }
                    .also {
                        val duration = (System.currentTimeMillis() - start)
                        if (duration > 10000) mcCount += duration
                        mcTime += duration
                    }
                    // check for emptiness
                    .assuming {
                        transitionSystem.run {
                            it.any { it.entries().asSequence().any { it.second.isSat() } }
                        }
                    }?.let { result ->
                transitionSystem.run {
                    var componentColors = ff
                    val union = ContinuousStateMap(0, transitionSystem.stateCount, transitionSystem.ff).apply {
                        for (map in result) {
                            var count = 0
                            for ((s, p) in map.entries()) {
                                this[s] = p
                                componentColors = componentColors or p
                                count += 1
                            }
                            //print("$count, ")
                        }
                        //println()
                    }

                    counter.push(componentColors)
                    universeQueue.add(union)
                }
            }
        }
/*
        println("Pivot time: $pivotTime")
        println("MC time: $mcTime")
        println("MC interesting time: $mcCount")
        println("Init time: $initTime")
        println("Reachability: ${reachTimer.get()}")*/

        universeExecutor.shutdown()

        return counter

    }
}

class NewBlockPartition(
        override val partitionId: Int,
        override val partitionCount: Int,
        private val blocksPerDimension: Int,
        odeModel: OdeModel,
        model: Model<Params>
) : Partition<Params>, Model<Params> by model {

    private val encoder = NodeEncoder(odeModel)

    val xBlockSize = Math.ceil((odeModel.variables[0].thresholds.size - 1) / blocksPerDimension.toDouble()).toInt()
    val yBlockSize = Math.ceil((odeModel.variables[1].thresholds.size - 1) / blocksPerDimension.toDouble()).toInt()

    override fun Int.owner(): Int {
        val x = (encoder.coordinate(this, 0) / xBlockSize)
        val y = (encoder.coordinate(this, 1) / yBlockSize)
        val blockIndex = blocksPerDimension * x + y
        return blockIndex % partitionCount
    }

}

class AdaptivePartition(
        override val partitionId: Int,
        override val partitionCount: Int,
        private val states: IntArray,
        model: Model<Params>
) : Partition<Params>, Model<Params> by model {

    val blockSize = 101
    val partitionSize = Math.max((states.size / partitionCount) + 1, 1000)

    override fun Int.owner(): Int {
        val index = states.binarySearch(this)
        if (index < 0) throw IllegalStateException("State not in this universe!")
        return (index / blockSize) % partitionCount
        //return index / partitionSize
    }

}
