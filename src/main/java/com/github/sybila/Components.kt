package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.operator.ComplementOperator
import com.github.sybila.checker.operator.ExistsUntilOperator
import com.github.sybila.huctl.DirectionFormula

// --------------------- GENERAL HELPER FUNCTIONS --------------------------

// General class hierarchy:
// States are represented by integer ID.
// Parameters are represented using the generic T/Params type.
// StateMap is a standard State -> Param mapping, but can have various (symbolic/explicit) implementations
// Operator is something like a lazy state map - it is a class that can compute results (based on other operators)
// and then return them (from the compute method).
// Channel is a know-it-all context which unites Model/Partition/Solver/Communicator into one context.

// shorthand for creating an And/Or/Not operators
//fun <T: Any> Channel<T>.And(left: Operator<T>, right: Operator<T>) = AndOperator(left, right, this)
//fun <T: Any> Channel<T>.Or(left: Operator<T>, right: Operator<T>) = OrOperator(left, right, this)
fun <T: Any> Channel<T>.Complement(left: Operator<T>, domain: Operator<T>) = ComplementOperator(domain, left, this)

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

fun StateMap<Params>.asOp(): Operator<Params> = ExplicitOperator(this)

//inline fun <T, R> List<T>.flatRun(action: T.() -> R): List<R> = this.map { it.run(action) }
inline fun <T, V, R> List<T>.zipRun(context: List<V>, action: V.(T) -> R): List<R>
        = this.zip(context).map { it.second.run { action(it.first) } }