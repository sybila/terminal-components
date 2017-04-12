package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.map.RangeStateMap
import com.github.sybila.checker.operator.*
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.ode.generator.det.DetOdeModel
import com.github.sybila.ode.generator.det.RectangleSet
import com.github.sybila.ode.model.OdeModel
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

// --------------------- GENERAL HELPER FUNCTIONS --------------------------

// General class hierarchy:
// States are represented by integer ID.
// Parameters are represented using the generic T/Params type.
// StateMap is a standard State -> Param mapping, but can have various (symbolic/explicit) implementations
// Operator is something like a lazy state map - it is a class that can compute results (based on other operators)
// and then return them (from the compute method).
// Channel is a know-it-all context which unites Model/Partition/Solver/Communicator into one context.

// shorthand for creating an And/Or/Not operators
fun <T: Any> Channel<T>.And(left: Operator<T>, right: Operator<T>) = AndOperator(left, right, this)
fun <T: Any> Channel<T>.Or(left: Operator<T>, right: Operator<T>) = OrOperator(left, right, this)
fun <T: Any> Channel<T>.Not(left: Operator<T>) = ComplementOperator(TrueOperator(this), left, this)

// forward reachability
fun <T: Any> Channel<T>.FWD(inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = false,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

// backward reachability
fun <T: Any> Channel<T>.BWD (inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = true,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

// operator which does not compute anything, only returns a fixed state map
// (f.e. you can use this to provide an operator which returns just one state)
class ExplicitOperator<out Params : Any>(
        private val data: StateMap<Params>
) : Operator<Params> {

    override fun compute(): StateMap<Params> = data

}

// check if results computed by given operator is empty
fun <T: Any> Channel<T>.isResultEmpty(op: Operator<T>) = op.compute().entries().asSequence().all { it.second.isNotSat() }

// ----------------------- MAIN ALGORITHM -------------------------------------

class LocalAlgorithm(
        parallelism: Int,
        private val useHeuristics: Boolean
) : Algorithm {

    override fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params> {
        val transitionSystem = SingletonChannel(DetOdeModel(model,
                createSelfLoops = !config.disableSelfLoops
        ).asSingletonPartition())

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

            logStream?.println("Transition system computed. Starting component search...")

            // counter is synchronized
            val counter = Count(this)

            // compute results
            val allStates = TrueOperator(this)
            startAction(allStates, counter)

            blockWhilePending()
            executor.shutdown()

            return counter
        }
    }

    val executor = Executors.newFixedThreadPool(parallelism)!!
    //private val executor = Executors.newFixedThreadPool(2)
    private val pending = ArrayList<Future<*>>()

    // This is the generic version of the terminal ssc algorithm:
    fun <T: Any> Channel<T>.paramRecursionTSCC(op: Operator<T>, paramCounts: Count<T>) {
        var universe = op
        while (!isResultEmpty(universe)) {
            val (v, vParams) = if (useHeuristics) choose(universe) else chooseSimple(universe)

            // operator for single state v
            val vOp = ExplicitOperator(v.asStateMap(vParams))

            val shouldTrim = v.predecessors(true).asSequence().fold(tt) { acc, t ->
                acc and (t.bound and vParams and universe.compute()[t.target]).not()
            }

            if (shouldTrim.isSat()) {
                universe = And(universe, Not(vOp))
                continue
            }
            println("Choose $v")

            // limit that restricts the whole state space to parameters associated with v
            val limit = ExplicitOperator(RangeStateMap(0 until stateCount, value = vParams, default = ff))


            // F - states reachable from v
            val F = And(universe, FWD(vOp))
            // B - states that can reach v but are also reachable from v
            val B = And(F, BWD(vOp))
            // F\B - states reachable from v but not reaching v - i.e. a potentially new terminal component
            // will be empty if v is on a terminal cycle
            val F_minus_B = And(F, Not(B))

            if (!isResultEmpty(F_minus_B)) {
                startAction(F_minus_B, paramCounts)
            }

            // BB - B' - All valid predecessors of F
            val BB = And(universe, BWD(F))

            // V \ BB restricted to the parameters of state v
            val V_minus_BB = And(And(universe, limit), Not(BB))

            // compute parameter union across the potential new component
            val componentParams = V_minus_BB.compute().entries().asSequence().fold(ff) { acc, (_, params) ->
                acc or params
            }

            if (componentParams.isSat()) {
                paramCounts.push(componentParams)
                startAction(V_minus_BB, paramCounts)
            }

            // cut the processed area out of the universe and repeat
            universe = And(universe, Not(limit))
        }
    }

    private var lastPrint = AtomicLong(System.currentTimeMillis())

    fun printActionProgress() {
        val last = lastPrint.get()
        if (System.currentTimeMillis() > last + 3000 && lastPrint.compareAndSet(last, System.currentTimeMillis())) {
            /*synchronized(pending) {
                println("Action queue: ${pending.size}")
            }*/
        }
    }

    fun <T: Any> Channel<T>.startAction(states: Operator<T>, paramCounts: Count<T>) {
        // start new task in an executor and save it into pending
        synchronized(pending) {
            val future = executor.submit {
                paramRecursionTSCC(states, paramCounts)
            }
            pending.add(future)
            printActionProgress()
        }
    }

    fun blockWhilePending() {
        // go through the pending tasks one by one, removing the ones that are
        // completed and terminating when all tasks are done
        do {
            val first = synchronized(pending) {
                pending.firstOrNull()
            }
            first?.let {
                it.get()
                synchronized(pending) {
                    pending.remove(it)
                }
                printActionProgress()
            }
        } while (first != null)
    }

    // find first non-empty state in operator results and return is lifted to an operator
    fun <T: Any> choose(op: Operator<T>): Pair<Int, T> {
        var max: Pair<Int, T>? = null
        var maxCovered: Int = 0
        op.compute().entries().forEach { (state, p) ->
            val params = p as RectangleSet
            val covered = params.weight()
            if (covered > maxCovered || (covered == maxCovered && state < max?.first ?: -1)) {
                max = state to p
                maxCovered = covered
            }
        }
        return max!!
    }

    fun <T: Any> chooseSimple(op: Operator<T>): Pair<Int, T> = op.compute().entries().next()

}