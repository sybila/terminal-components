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

            val channels = (0 until parallelism).map { this }//.asUniformPartitions().connectWithSharedMemory()
                    .asBlockPartitions(parallelism * 6).connectWithSharedMemory()

            val fullStateSpace = (0 until stateCount).asStateMap(tt)

            val initialSpace = channels.flatRun { fullStateSpace.restrictToPartition() }

            val counter = Count(this)

            paramRecursionTSCC(channels, initialSpace, counter)
            executor.shutdown()

            println("MC time: $modelChecking Max: $maxTime Avr: ${modelChecking/count}")

            return counter
        }

    }

    val executor = Executors.newFixedThreadPool(parallelism)!!

    var modelChecking = 0L
    var maxTime = 0L
    var count = 0
    var start = 0L

    fun Model<Params>.paramRecursionTSCC(channels: List<Channel<Params>>, states: List<StateMap<Params>>, counter: Count<Params>) {
        val universes = ArrayDeque<List<StateMap<Params>>>()
        universes.push(states)
        while (universes.isNotEmpty()) {

            var universe = universes.pop()//states

            fun List<StateMap<Params>>.isStrongEmpty() = this.zip(channels).map { (map, solver) ->
                executor.submit<Boolean> {
                    solver.run {
                        map.entries().asSequence().all { it.second.isNotSat() }
                    }
                }
            }.all { it.get() }

            fun List<Operator<Params>>.compute(): List<StateMap<Params>> = this.also { start = System.currentTimeMillis() }.map { op ->
                executor.submit<StateMap<Params>> {
                    op.compute()
                }
            }.map { it.get() }.also {
                val total = System.currentTimeMillis() - start
                modelChecking += total
                count += 1
                if (total > maxTime) {
                    maxTime = total
                    println("New max: $maxTime")
                }
            }

            fun Int.isSink() = this.successors(true).asSequence().fold(tt) { acc, t ->
                acc and (if (t.target == this) t.bound else t.bound.not())
            }

            fun List<StateMap<Params>>.choose() = this.map { map ->
                executor.submit<Pair<Int, Params>> {
                    var max: Pair<Int, Params>? = null
                    var maxWeight: Int = 0
                    var isSink: Boolean = false
                    for ((state, p) in map.entries()) {
                        val sink = state.isSink()
                        val weight = p.weight()
                        //if (weight > maxWeight || (weight == maxWeight && state < max?.first ?: -1)) {
                        if ((sink.isSat() && !isSink) || (!isSink && (weight > maxWeight || (weight == maxWeight && state < max?.first ?: -1)))) {
                            /*if (sink.isSat()) {
                                println("Found sink in $state")
                            }*/
                            max = state to p
                            maxWeight = weight
                            isSink = sink.isSat()
                        }
                    }
                    max
                }
            }.fold<Future<Pair<Int, Params>?>, Pair<Int, Params>?>(null) { current, future ->
                /*future.get()?.let { new ->
                    current?.assuming {
                        val max = it.second.weight()
                        val newMax = new.second.weight()
                        it.first.isSink() || max > newMax || (max == newMax && it.first < new.first)
                    } ?: new
                } ?: current*/
                val new = future.get()
                val winner = current?.assuming { it.first.isSink().isSat() || (new?.first?.isSink()?.isNotSat() ?: true && it.second.weight() > new?.second?.weight() ?: 0) } ?: new
                winner
            }!!

            //println("Start recursion: ${universe.isStrongEmpty()}")

            while (!universe.isStrongEmpty()) {

                //println("Iteration")

                val (v, vParams) = universe.choose()

                val vOp = channels.flatRun { v.asStateMap(vParams).restrictToPartition().asOp() }

                var trimmed = false
                val somePartition = channels[0]
                somePartition.run {
                    val shouldTrim = v.predecessors(true).asSequence().fold(tt) { acc, t ->
                        acc and (t.bound and vParams and universe[t.target.owner()][t.target]).not()
                    }

                    if (shouldTrim.isSat()) {
                        universe = universe.zip(vOp).zipRun(channels) { (universe, vOp) ->
                            And(universe.asOp(), Not(vOp))
                        }.compute()
                        trimmed = true
                    }


                }

                if (trimmed) continue

                println("Chosen: $v")

                val limits = channels.flatRun {
                    RangeStateMap(0 until stateCount, value = vParams, default = ff).restrictToPartition().asOp()
                }

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
                    //paramRecursionTSCC(channels, it, counter)
                    universes.push(it)
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
                    //paramRecursionTSCC(channels, V_minus_BB_result, counter)
                    universes.push(V_minus_BB_result)
                }

                universe = universe.zip(limits).zipRun(channels) { (universe, limit) ->
                    And(universe.asOp(), Not(limit))
                }.compute()

            }
        }

    }

}