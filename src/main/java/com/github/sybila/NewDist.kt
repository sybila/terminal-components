package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.Partition
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.assuming
import com.github.sybila.checker.map.mutable.ContinuousStateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.UniformPartition
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
        val magic = when (config.heuristics) {
            HeuristicType.NONE -> None(transitionSystem)
            HeuristicType.CARDINALITY -> Cardinality(transitionSystem)
            HeuristicType.CARDINALITY_STRUCTURE -> Magic(transitionSystem)
        }

        val allStates = transitionSystem.run { TrueOperator(this).compute() }

        val universeQueue = ArrayDeque<StateMap<Params>>()
        universeQueue.push(allStates)

        while (universeQueue.isNotEmpty()) {
            val universe = universeQueue.poll()

            // restrict state space to this universe
            val restrictedSystem = ExplicitOdeModel(transitionSystem, universe, universeExecutor)

            val parallelism = config.parallelism//Math.min(config.parallelism, Math.max(1, states.size / 1000))

            // compute universe partitioning
            val partitions = (0 until parallelism).map {
                if (config.partitioning < 0) {
                    NewBlockPartition(it, parallelism, parallelism + 1, model, restrictedSystem)
                } else if (config.partitioning > 0) {
                    NewBlockPartition(it, parallelism, config.partitioning, model, restrictedSystem)
                } else {
                    UniformPartition(it, parallelism, restrictedSystem)
                }
                //BlockPartition(it, config.parallelism, 100, restrictedSystem)
                //AdaptivePartition(it, config.parallelism, states, restrictedSystem)
                //NewBlockPartition(it, parallelism, config.blocksPerDimension, model, restrictedSystem)
            }.connectWithNoCopy()//.connectWithSharedMemory()

            // find pivots
            var pivotCount = 0
            val pivots = transitionSystem.run {
                val pivots = HashStateMap(ff)
                var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

                do {
                    pivotCount += 1
                    val (pivot, params) = magic.findMagic(universe, uncovered)
                    pivots[pivot] = params
                    uncovered = uncovered and params.not()
                } while (uncovered.isSat())

                pivots
            }

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

            F_minus_B
                    // compute in parallel
                    .map { universeExecutor.submit<StateMap<Params>> { it.compute() } }.map { it.get() }
                    // check for emptiness
                    .assuming {
                        transitionSystem.run {
                            it.any { it.entries().asSequence().any { it.second.isSat() } }
                        }
                    }?.let { result ->
                val union = ContinuousStateMap(0, transitionSystem.stateCount, transitionSystem.ff).apply {
                    for (map in result) {
                        for ((s, p) in map.entries()) {
                            this[s] = p
                        }
                    }
                }
                universeQueue.add(union)
            }

            val BB = F.zipRun(partitions) { BWD(it) }

            val V_minus_BB = BB.zip(universeOp).zipRun(partitions) { (BB, universe) ->
                Complement(BB, universe)
            }

            V_minus_BB
                    // compute in parallel
                    .map { universeExecutor.submit<StateMap<Params>> { it.compute() } }.map { it.get() }
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
                            for ((s, p) in map.entries()) {
                                this[s] = p
                                componentColors = componentColors or p
                            }
                        }
                    }

                    counter.push(componentColors)
                    universeQueue.add(union)
                }
            }
        }

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

    private val dimensionMultipliers: IntArray = IntArray(odeModel.variables.size)

    init {
        var blockCount = 1
        for (b in dimensionMultipliers.indices) {
            dimensionMultipliers[b] = blockCount
            blockCount *= blocksPerDimension
        }
    }

    val blockSizes: IntArray = kotlin.IntArray(odeModel.variables.size) {
        Math.ceil((odeModel.variables[it].thresholds.size - 1) / blocksPerDimension.toDouble()).toInt()
    }

    private val encoder = NodeEncoder(odeModel)

    override fun Int.owner(): Int {
        val blockIndex = dimensionMultipliers.indices.fold(0) { index, dim ->
            val dimIndex = encoder.coordinate(this, dim) / blockSizes[dim]
            index + dimensionMultipliers[dim] * dimIndex
        }
        return blockIndex % partitionCount
    }

}
/*
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
*/