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

fun <T: Any> List<Channel<T>>.allParams(map: List<StateMap<T>>): T {
    return this.zip(map).fold(this.first().ff) { p, (c, map) ->
        c.run {
            val inner = map.entries().asSequence().fold(ff) { a, b -> a or b.second }
            p or inner
        }
    }
}

fun <T: Any> Model<T>.asSingletonChannel() = SingletonChannel(this.asSingletonPartition())