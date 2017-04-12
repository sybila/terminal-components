package com.github.sybila

import com.github.sybila.checker.*
import com.github.sybila.checker.channel.connectWithSharedMemory
import com.github.sybila.checker.map.RangeStateMap
import com.github.sybila.checker.partition.asBlockPartitions
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.generator.det.RectangleSet
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

fun Params.weight() = this.values.cardinality()/*this.fold(0.0) { acc, rect ->
    acc + rect.asIntervals().map { Math.abs(it[1] - it[0]) }.fold(1.0) { acc, dim -> acc * dim }
}*/

fun <T: Any> StateMap<T>.asOp(): Operator<T> = ExplicitOperator(this)

inline fun <T, R> List<T>.flatRun(action: T.() -> R): List<R> = this.map { it.run(action) }
inline fun <T, V, R> List<T>.zipRun(context: List<V>, action: V.(T) -> R): List<R>
        = this.zip(context).map { it.second.run { action(it.first) } }

class DistAlgorithm(
        private val parallelism: Int
) : Algorithm {

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {

        val transitionSystem = DetOdeModel(model)

        transitionSystem.run {

            // transition system is not synchronized, however, it is read-safe.
            // hence if we pre-compute all transitions before actually starting the algorithm,
            // we can safely perform operations in parallel.
            (0 until stateCount).forEach {
                it.predecessors(true)
                it.predecessors(false)
                it.successors(true)
                it.successors(false)
            }

            val channels = (0 until parallelism).map { this }.asBlockPartitions(stateCount/16).connectWithSharedMemory()

            val fullStateSpace = (0 until stateCount).asStateMap(tt)

            val initialSpace = channels.flatRun { fullStateSpace.restrictToPartition() }

            val counter = Count(this)

            paramRecursionTSCC(channels, initialSpace, counter)
            executor.shutdown()

            return counter
        }

    }

    val executor = Executors.newFixedThreadPool(parallelism)!!

    fun Model<Params>.paramRecursionTSCC(channels: List<Channel<Params>>, states: List<StateMap<Params>>, counter: Count<Params>) {
        var universe = states

        fun List<StateMap<Params>>.isStrongEmpty() = this.zip(channels).map { (map, solver) ->
            executor.submit<Boolean> {
                solver.run {
                    map.entries().asSequence().all { it.second.isNotSat() }
                }
            }
        }.all { it.get() }

        fun List<Operator<Params>>.compute(): List<StateMap<Params>> = this.map { op ->
            executor.submit<StateMap<Params>> {
                op.compute()
            }
        }.map { it.get() }

        fun List<StateMap<Params>>.choose() = this.map { map ->
            executor.submit<Pair<Int, Params>> {
                var max: Pair<Int, Params>? = null
                var maxWeight: Int = 0
                map.entries().forEach { (state, p) ->
                    val weight = p.weight()
                    if (weight > maxWeight || (weight == maxWeight && state < max?.first ?: -1)) {
                        max = state to p
                        maxWeight = weight
                    }
                }
                max
            }
        }.fold<Future<Pair<Int, Params>?>, Pair<Int, Params>?>(null) { current, future ->
            future.get()?.let { new ->
                current?.assuming {
                    val max = it.second.weight()
                    val newMax = new.second.weight()
                    max > newMax || (max == newMax && it.first < new.first)
                } ?: new
            } ?: current
            /*val new = future.get()
            current?.assuming { it.second.weight() > new?.second?.weight() ?: 0 } ?: new*/
        }!!

        //println("Start recursion: ${universe.isStrongEmpty()}")

        while (!universe.isStrongEmpty()) {

            //println("Iteration")

            val (v, vParams) = universe.choose()

            println("Chosen $v")

            val limits = channels.flatRun {
                RangeStateMap(0 until stateCount, value = vParams, default = ff).restrictToPartition().asOp()
            }

            val vOp = channels.flatRun { v.asStateMap(vParams).restrictToPartition().asOp() }

            val F = universe.zip(vOp).zipRun(channels) { (universe, vOp) ->
                And(universe.asOp(), FWD(vOp))
            }

            val B = F.zip(vOp).zipRun(channels) { (F, vOp) ->
                And(F, BWD(vOp))
            }

            val F_minus_B = F.zip(B).zipRun(channels) { (F, B) ->
                And(F, Not(B))
            }

            F_minus_B.compute().assuming { !it.isStrongEmpty() }?.let {
                paramRecursionTSCC(channels, it, counter)
            }

            val BB = universe.zip(F).zipRun(channels) { (universe, F) ->
                And(universe.asOp(), BWD(F))
            }

            val V_minus_BB = universe.zip(limits).zip(BB).zipRun(channels) { (pair, BB) ->
                val (uni, limit) = pair
                And(And(uni.asOp(), limit), Not(BB))
            }

            val V_minus_BB_result = V_minus_BB.compute()

            //println("V/BB: $V_minus_BB_result")

            val componentParams = V_minus_BB_result.asSequence().flatMap { it.entries().asSequence() }
                    .fold<Pair<Int, Params>, Params>(RectangleSet(doubleArrayOf(), doubleArrayOf(), BitSet())) { acc, (_, params) ->
                acc or params
            }

            if (componentParams.isSat()) {
                counter.push(componentParams)
                paramRecursionTSCC(channels, V_minus_BB_result, counter)
            }

            universe = universe.zip(limits).zipRun(channels) { (universe, limit) ->
                And(universe.asOp(), Not(limit))
            }.compute()

        }

    }

}