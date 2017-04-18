package com.github.sybila

import com.github.sybila.checker.*
import com.github.sybila.checker.channel.connectWithSharedMemory
import com.github.sybila.checker.map.mutable.ContinuousStateMap
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.huctl.Formula
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.generator.det.RectangleSet
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors

/*fun Params.weight() = this.values.cardinality()*//*this.fold(0.0) { acc, rect ->
    acc + rect.asIntervals().map { Math.abs(it[1] - it[0]) }.fold(1.0) { acc, dim -> acc * dim }
}*/

fun Params.weight(): Double {
    /*var sum: Double = 0.0
    values.stream().forEach {
        val x = it % modifier
        val y = it / modifier
        val sizeX = thresholdsX[x+1] - thresholdsX[x]
        val sizeY = thresholdsY[y+1] - thresholdsY[y]
        sum += sizeX * sizeY
    }
    return sum*/
    return values.cardinality().toDouble()
}

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

           /* val channels = (0 until parallelism).map { this }.asUniformPartitions().connectWithSharedMemory()
                    //.asBlockPartitions(parallelism * 6).connectWithSharedMemory()

            val fullStateSpace = (0 until stateCount).asStateMap(tt)

            val initialSpace = channels.flatRun { fullStateSpace.restrictToPartition() }
*/
            val counter = Count(this)

            newMethod(counter, parallelism)
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

    class AdaptivePartition(
            override val partitionId: Int,
            override val partitionCount: Int,
            private val states: IntArray,
            model: Model<Params>
    ) : Partition<Params>, Model<Params> by model {

        val partitionSize = (states.size / partitionCount) + 1

        override fun Int.owner(): Int {
            val index = states.binarySearch(this)
            if (index < 0) throw IllegalStateException("State not in this universe!")
            return index / partitionSize
        }

    }

    class Universe(
            private val universe: StateMap<Params>,
            private val original: Model<Params>
    ) : Model<Params>, Solver<Params> by original {

        override val stateCount: Int = original.stateCount

        val predecessorsFwd = HashMap<Int, List<Transition<Params>>>().apply {
            for (s in 0 until stateCount) {
                original.run {
                    val r = s.predecessors(true).asSequence().map {
                        val newBound = it.bound and universe[it.target] and universe[s]
                        //println("new bound $newBound")
                        if (newBound.isSat()) it.copy(bound = newBound)
                        else null
                    }.filterNotNull()
                    this@apply.put(s, r.toList())
                }
            }
        }

        val predecessorsBwd = HashMap<Int, List<Transition<Params>>>().apply {
            for (s in 0 until stateCount) {
                original.run {
                    val r = s.predecessors(false).asSequence().map {
                        val newBound = it.bound and universe[it.target] and universe[s]
                        if (newBound.isSat()) it.copy(bound = newBound)
                        else null
                    }.filterNotNull()
                    this@apply.put(s, r.toList())
                }
            }
        }

        val successorsFwd = HashMap<Int, List<Transition<Params>>>().apply {
            for (s in 0 until stateCount) {
                original.run {
                    val r = s.successors(true).asSequence().map {
                        val newBound = it.bound and universe[it.target] and universe[s]
                        if (newBound.isSat()) it.copy(bound = newBound)
                        else null
                    }.filterNotNull()
                    this@apply.put(s, r.toList())
                }
            }
        }

        val successorsBwd = HashMap<Int, List<Transition<Params>>>().apply {
            for (s in 0 until stateCount) {
                original.run {
                    val r = s.successors(false).asSequence().map {
                        val newBound = it.bound and universe[it.target] and universe[s]
                        if (newBound.isSat()) it.copy(bound = newBound)
                        else null
                    }.filterNotNull()
                    this@apply.put(s, r.toList())
                }
            }
        }

        override fun Formula.Atom.Float.eval(): StateMap<Params> = original.run { this@eval.eval() }

        override fun Formula.Atom.Transition.eval(): StateMap<Params> = original.run { this@eval.eval() }

        override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<Params>> {
            if (!timeFlow) {
                return predecessorsBwd[this]?.iterator() ?: emptyList<Transition<Params>>().iterator()
            } else {
                return predecessorsFwd[this]?.iterator()  ?: emptyList<Transition<Params>>().iterator()
            }
        }

        override fun Int.successors(timeFlow: Boolean): Iterator<Transition<Params>> {
            if (!timeFlow) {
                return successorsBwd[this]?.iterator()  ?: emptyList<Transition<Params>>().iterator()
            } else {
                return successorsFwd[this]?.iterator()  ?: emptyList<Transition<Params>>().iterator()
            }
        }


    }

    fun Model<Params>.newMethod(counter: Count<Params>, partitionCount: Int) {
        val universes = ArrayDeque<ContinuousStateMap<Params>>()
        universes.add(ContinuousStateMap(0, stateCount, ff).apply {
            for (s in 0 until stateCount) set(s, tt)
        })
        val heursitic = Heuristics(this)

        var universeModel = this

        while (universes.isNotEmpty()) {

            println("Remaining: ${universes.size}")

            fun List<StateMap<Params>>.isStrongEmpty(channels: List<Channel<Params>>)
                    = this.zip(channels).map { (map, solver) ->
                executor.submit<Boolean> {
                    solver.run {
                        map.entries().asSequence().all { it.second.isNotSat() }
                    }
                }
            }.all { it.get() }

            fun Int.isSink() = this.successors(true).asSequence().fold(tt) { acc, t ->
                acc and (if (t.target == this) t.bound else t.bound.not())
            }

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

            val universe = universes.pop()

            universeModel = Universe(universe, universeModel)

            var pivotCount = 0
            var trimCount = 0
            val pivots = HashStateMap(ff)
            var uncovered = universe.entries().asSequence().fold(ff) { a, b -> a or b.second }

            do {
                //val magic = heursitic.findMagic(universe, uncovered)

                pivotCount += 1
                //println("Magic: ${magic.magic}")
                //pivots[magic.state] = magic.params
                //uncovered = uncovered and magic.params.not()

                /*val weights = universe.entries().asSequence().map { it.first to (it.second and uncovered).weight() }.toList()
                val maxWeight: Double = weights.maxBy { it.second }?.second ?: 0.0
                val states = weights.filter { it.second == maxWeight }.map { it.first }.sorted()
                val pivot = states[states.size / 2]
                val pivotColors = universe[pivot] and uncovered

                val canTrim = pivot.predecessors(true).asSequence().fold(pivotColors) { acc, t ->
                    acc and (t.bound and pivotColors).not()
                }

                if (canTrim.isSat()) {
                    universe[pivot] = universe[pivot] and canTrim.not()
                    trimCount += 1
                } else {
                    pivotCount += 1
                    pivots[pivot] = pivotColors
                    uncovered = uncovered and pivotColors.not()
                }*/
            } while (uncovered.isSat())

            val allStates = universe.states().asSequence().toList().toIntArray()
            allStates.sort()

            val channels = (0 until partitionCount).map {
                AdaptivePartition(it, partitionCount, allStates, universeModel)
            }.connectWithSharedMemory()
            val partition = channels.first()

            /*println("pivots: $pivotCount; trims: $trimCount")
            partition.run {
                println("pivot partitions: ${pivots.states().asSequence().map { it.owner() }.toList()}")
                println("universe partitions: ${(0 until partitionCount).map { partition ->
                    universe.states().asSequence().count { it.owner() == partition }
                }}")
            }*/
            //println(pivots.states().asSequence().toList())

            val vOp = channels.flatRun { pivots.restrictToPartition().asOp() }
            val universeOp = channels.flatRun { universe.restrictToPartition().asOp() }

            val F = universeOp.zip(vOp).zipRun(channels) { (universe, vOp) ->
                And(universe, FWD(vOp))
                //FWD(vOp)
            }

            //println("F: ${F[0].compute().entries().asSequence().filter { it.second.isSat() }.map { it.first }.toList()}")

            val BWDvOp = vOp.zipRun(channels) {
                BWD(it)
            }

            /*partition.run {
                println("BWDvOp partitions: ${BWDvOp.compute().map { it.entries().asSequence().count { it.second.isSat() } }}")
            }*/

            val B = F.zip(BWDvOp).zipRun(channels) { (F, vOp) ->
                And(F, vOp)
            }

            val Bcomplement = B.zip(universeOp).zipRun(channels) { (B, universe) ->
                Complement(B, universe)
            }

            val F_minus_B = F.zip(Bcomplement).zipRun(channels) { (F, B) ->
                And(F, Complement(B, TrueOperator(this)))
                //And(F, B)
            }

            F_minus_B.compute().assuming { !it.isStrongEmpty(channels) }?.let {
                //paramRecursionTSCC(channels, it, counter)
                val push = ContinuousStateMap(0, stateCount, ff).apply {
                    for (p in F_minus_B.compute()) for (s in p.entries()) set(s.first, s.second)
                }
                //println("Push 1: ${push.states().asSequence().toList()}")
                universes.push(push)
            }

            val BB = universeOp.zip(F).zipRun(channels) { (universe, F) ->
                And(universe, BWD(F))
                //BWD(F)
            }

            //println("BB: ${BB[0].compute().entries().asSequence().filter { it.second.isSat() }.map { it.first }.toList()}")

            val V_minus_BB = universeOp.zip(BB).zipRun(channels) { (universe, BB) ->
                And(universe, Complement(BB, TrueOperator(this)))
                //Complement(BB, universe)
            }

            val V_minus_BB_result = V_minus_BB.compute()

            //println("V/BB: $V_minus_BB_result")

            val componentParams = V_minus_BB_result.asSequence().flatMap { it.entries().asSequence() }
                    .fold(RectangleSet(doubleArrayOf(), doubleArrayOf(), BitSet())) { acc, (_, params) ->
                        acc or params
                    }

            if (componentParams.isSat()) {
                counter.push(componentParams)
                //paramRecursionTSCC(channels, V_minus_BB_result, counter)
                //universes.push(V_minus_BB_result)
                val push = ContinuousStateMap(0, stateCount, ff).apply {
                    for (p in V_minus_BB_result) for (s in p.entries()) set(s.first, s.second)
                }
                //println("Push 2: ${V_minus_BB_result[0].entries().asSequence().filter { it.second.isSat() }.map { it.first }.toList()}")
                universes.push(push)
            }
        }
    }
/*
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

            fun List<StateMap<Params>>.choose(): Pair<Int, Params> {
                val weights = this.asSequence().flatMap { it.entries().asSequence().map { it.first to it.second.weight() } }.toList()
                val maxWeight = weights.maxBy { it.second }?.second ?: 0
                val states = weights.filter { it.second == maxWeight }.map { it.first }.sorted()
                val vIndex = states[states.size / 2]
                return vIndex to this.find { vIndex in it }!!.get(vIndex)
            }

            /*
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
            */

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
                            And(universe.asOp(), Complement(vOp))
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
                    And(F, Complement(B))
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
                    And(And(uni.asOp(), limit), Complement(BB))
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
                    And(universe.asOp(), Complement(limit))
                }.compute()

            }
        }

    }
*/
}