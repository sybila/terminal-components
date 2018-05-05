package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Model
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.operator.*
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.DirectionFormula

// These are mostly helper functions for working with states/operators:

fun <T: Any> Channel<T>.intersect(left: Operator<T>, right: Operator<T>) = AndOperator(left, right, this)
fun <T: Any> Channel<T>.union(left: Operator<T>, right: Operator<T>) = OrOperator(left, right, this)
fun <T: Any> Channel<T>.complement(against: Operator<T>, inner: Operator<T>) = ComplementOperator(against, inner, this)

fun <T: Any> Channel<T>.reachForward(inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = false,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

fun <T: Any> Channel<T>.reachBackward(inner: Operator<T>) = ExistsUntilOperator(
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

fun <T: Any> StateMap<T>.asOperator() = ExplicitOperator(this)
fun <T: Any> Channel<T>.allParams(map: StateMap<T>) = map.entries().asSequence().fold(ff) { a, b -> a or b.second }

fun <T: Any> Channel<T>.isEmpty(map: StateMap<T>) = map.entries().asSequence().any { it.second.isSat() }

fun <T: Any> Model<T>.asSingletonChannel() = SingletonChannel(this.asSingletonPartition())