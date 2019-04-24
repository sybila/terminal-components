package com.github.sybila

import com.github.sybila.checker.*
import com.github.sybila.checker.operator.LazyOperator
import com.github.sybila.huctl.DirectionFormula
import java.util.HashSet
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

val reachTime = AtomicLong(0)
val reachCount = AtomicLong(0)

class ExistsUntilOperator<out Params : Any>(
        timeFlow: Boolean, direction: DirectionFormula, weak: Boolean,
        pathOp: Operator<Params>?, reach: Operator<Params>, partition: Channel<Params>,
        private val executor: ExecutorService
) : LazyOperator<Params>(partition, {

    val start = System.currentTimeMillis()
    val path = pathOp?.compute()

    val storage = (0 until partitionCount).map { newLocalMutableMap(it) }
    val result = storage[partitionId]

    val recompute = HashSet<Int>()
    val send = HashSet<Int>()

    //load local data
    if (!weak) {
        for ((state, value) in reach.compute().entries()) {
            result.setOrUnion(state, value)
            recompute.add(state)
        }
    } else {
        val r = reach.compute()
        (0 until stateCount).filter { it in this }.forEach { state ->
            val existsWrongDirection = state.successors(timeFlow).asSequence().fold(ff) { a, t ->
                if (!direction.eval(t.direction)) a or t.bound else a
            }
            val value = r[state] or existsWrongDirection
            if (value.canSat() && result.setOrUnion(state, value)) {
                recompute.add(state)
            }
        }
    }

    CheckerStats.setOperator("ExistsUntil")

    var received: List<Pair<Int, Params>>? = null

    do {
        received?.forEach {
            val (state, value) = it
            val withPath = if (path != null) value and path[state] else value
            if (withPath.canSat() && result.setOrUnion(state, withPath)) {
                recompute.add(it.first)
            }
        }

        do {
            var lastPrint = System.currentTimeMillis()
            /*if (recompute.size > 100) {
                println("Recompute: ${recompute.size}")
            }*/
            val iteration = recompute.toList()
            recompute.clear()
            val chunk = 2
            val chunks = (iteration.size / chunk) + 1
            reachCount.incrementAndGet()
            //println("Chunks: $chunks")
            (0 until chunks).map { cid ->
                executor.submit<Set<Int>> {
                    val todo = HashSet<Int>()
                    for (s in (cid * chunk) until Math.min(iteration.size, (cid+1)*chunk)) {
                        val state = iteration[s]
                        val value = synchronized(result) { result[state] }
                        for ((predecessor, dir, bound) in state.predecessors(timeFlow)) {
                            if (direction.eval(dir)) {
                                val owner = predecessor.owner()
                                if (owner == partitionId) {   //also consider path
                                    val witness = if (path != null) {
                                        value and bound and path[predecessor]
                                    } else value and bound
                                    if (witness.canSat() && synchronized(result) { result.setOrUnion(predecessor, witness) }) {
                                        todo.add(predecessor)
                                    }
                                } else {    //path will be handled by receiver
                                    val witness = value and bound
                                    if (witness.canSat() && storage[owner].setOrUnion(predecessor, witness)) {
                                        send.add(predecessor)
                                    }
                                }
                            }
                        }
                    }
                    todo
                }
            }.forEach { future ->
                recompute.addAll(future.get())
            }

            var i = 0
            /*for (state in iteration) {
                i += 1
                /*if (i % 1000 == 0 && lastPrint + 2000 < System.currentTimeMillis()) {
                    lastPrint = System.currentTimeMillis()
                    println("Computed: $i/${iteration.size}")
                }*/
                val value = result[state]
                for ((predecessor, dir, bound) in state.predecessors(timeFlow)) {
                    if (direction.eval(dir)) {
                        val owner = predecessor.owner()
                        if (owner == partitionId) {   //also consider path
                            val witness = if (path != null) {
                                value and bound and path[predecessor]
                            } else value and bound
                            if (witness.canSat() && result.setOrUnion(predecessor, witness)) {
                                recompute.add(predecessor)
                            }
                        } else {    //path will be handled by receiver
                            val witness = value and bound
                            if (witness.canSat() && storage[owner].setOrUnion(predecessor, witness)) {
                                send.add(predecessor)
                            }
                        }
                    }
                }
            }*/
        } while (recompute.isNotEmpty())
        //all local computation is done - exchange info with other workers!

        received = mapReduce(storage.prepareFilteredTransmission(partitionId, send))
        send.clear()
    } while (received != null)

    reachTime.addAndGet(System.currentTimeMillis() - start)
    result

})

internal fun <Params : Any> List<StateMap<Params>>.prepareFilteredTransmission(
        partitionId: Int, include: Set<Int>
): Array<List<Pair<Int, Params>>?>
        = this  .mapIndexed { i, map ->
    if (i == partitionId) null else map.entries().asSequence().filter { it.first in include }.toList()
}
        .map { if (it?.isEmpty() ?: true) null else it }.toTypedArray()
