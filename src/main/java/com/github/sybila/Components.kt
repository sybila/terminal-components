package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Operator
import com.github.sybila.checker.Partition
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.operator.*
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.ode.generator.LazyStateMap
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import java.io.File

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

// find first non-empty state in operator results and return is lifted to an operator
fun <T: Any> Channel<T>.choose(op: Operator<T>): Pair<Int, T> = op.compute().entries().asSequence()
        .first { it.second.isSat() }

// ----------------------- MAIN ALGORITHM -------------------------------------

// This is the generic version of the terminal ssc algorithm:
fun <T: Any> Channel<T>.paramRecursionTSCC(op: Operator<T>, paramCounts: Count<T>) {
    var universe = op
    while (!isResultEmpty(universe)) {
        val (v, vParams) = choose(universe)    // first non empty state (or error if empty)
        val vOp = ExplicitOperator(v.asStateMap(vParams))

        // F - states reachable from v
        val F = And(universe, FWD(vOp))
        // B - states that can reach v but are also reachable from v
        val B = And(F, BWD(vOp))
        // F\B - states reachable from v but not reaching v - i.e. a potentially new terminal component
        val F_minus_B = And(F, Not(B))

        if (!isResultEmpty(F_minus_B)) {
            paramRecursionTSCC(F_minus_B, paramCounts)
        }

        // BB - B' - All valid predecessors of F
        val BB = And(universe, BWD(F))

        // V \ BB
        val V_minus_BB_full = And(universe, Not(BB)).compute()
        // V \ BB restricted to the parameters of state v
        val V_minus_BB = ExplicitOperator(LazyStateMap(stateCount, ff) {
            V_minus_BB_full[it] and vParams
        })

        // compute parameter union across the potential new component
        val componentParams = V_minus_BB.compute().entries().asSequence().fold(ff) { acc, (_, params) ->
            acc or params
        }

        if (componentParams.isSat()) {
            paramCounts.push(componentParams)
            paramRecursionTSCC(V_minus_BB, paramCounts)
        }

        // cut the two processed areas out of the universe and repeat
        universe = And(universe, Not(Or(F_minus_B, BB)))
    }
}

// ---------------------- PLUMBING TO PUT IT ALL TOGETHER ------------------------

fun main(args: Array<String>) {

    val modelFile = File(args[0])

    println("Computing model approximation...")

    val odeModel = Parser().parse(modelFile).computeApproximation(fast = false, cutToRange = false)

    println("Approximation done. Starting component search...")

    val transitionSystem = SingletonChannel(RectangleOdeModel(odeModel, createSelfLoops = true).asSingletonPartition())

    // enter transition system context
    transitionSystem.run {
        val counter = Count(this)

        // compute results
        paramRecursionTSCC(TrueOperator(this), counter)

        println("Component search done.")
        println("Max number of components: ${counter.max}.")
        println("Min number of components: ${counter.min}.")

        for (i in 0 until counter.size) {                                               // print countTSCC for each parameter set
            println("${i+1} terminal components: ${counter[i]}")
        }
    }

}