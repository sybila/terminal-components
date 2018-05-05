package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.connectWithSharedMemory
import com.github.sybila.checker.map.mutable.HashStateMap
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.checker.partition.asUniformPartitions
import com.github.sybila.ode.model.OdeModel
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class DistAlgorithm<T: Any>(config: Config, allStates: ExplicitOdeFragment<T>, odeModel: OdeModel) : Algorithm<T>(config, allStates, odeModel) {

    private val count = Count(allStates)
    private val store = ComponentStore(allStates)

    private val executor = Executors.newCachedThreadPool()
    private val pending = ArrayList<Future<*>>()

    override fun computeComponents(): ResultSet {
        startAction(TrueOperator(allStates.asSingletonPartition()).compute())
        blockWhilePending()

        val result = store.getComponentMapping(count).mapIndexed { i, map -> "${i+1} attractor(s)" to listOf(map) }.toMap()

        return allStates.exportResults(odeModel, result)
    }

    private fun runAction(universe: StateMap<T>) {
        allStates.restrictTo(universe).run {
            val universeSize = universe.entries().asSequence().map { it.second.volume() }.sum()
            config.logStream?.println("Universe size: $universeSize")
            val channels = (0 until config.parallelism).map { this }.asUniformPartitions().connectWithSharedMemory()
            val pivots = pivot.choose(universe)

            val localPivots = channels.runOn { pivots.restrictToPartition().asOperator() }
            val localUniverse = channels.runOn { universe.restrictToPartition().asOperator() }
            val forward = channels.zip(localPivots).mapRunOn { reachForward(it) }
            val backward = channels.zip(forward.zip(localPivots)).mapRunOn { (f, p) -> intersect(reachBackward(p), f) }
            val forwardNotBackward = channels.zip(forward.zip(backward)).mapRunOn { (f, b) -> complement(f, b) }.computeAll()

            val reachableComponentParams = channels.allParams(forwardNotBackward)
            store.push(forward.computeAll().joinMaps(), reachableComponentParams.not() )

            if (reachableComponentParams.isSat()) {
                startAction(forwardNotBackward.joinMaps())
            }

            val backwardFromForward = channels.zip(forward).mapRunOn { reachBackward(it) }
            val cantReachForward = channels.zip(localUniverse.zip(backwardFromForward)).mapRunOn { (u, b) -> complement(u, b) }.computeAll()

            val unreachableComponentsParams = channels.allParams(cantReachForward)

            if (unreachableComponentsParams.isSat()) {
                count.push(unreachableComponentsParams)
                startAction(cantReachForward.joinMaps())
            }

        }
    }

    private fun startAction(universe: StateMap<T>) {
        synchronized(pending) {
            pending.add(executor.submit {
                runAction(universe)
            })
        }
    }

    private fun blockWhilePending() {
        do {
            val waited = synchronized(pending) {
                pending.firstOrNull()
            }?.let { waitFor ->
                waitFor.get()
                synchronized(pending) { pending.remove(waitFor)}
                Unit
            }
        } while (waited != null)
    }

    override fun close() {
        executor.shutdown()
    }

    private fun List<StateMap<T>>.joinMaps(): StateMap<T> {
        allStates.run {
            val result = HashStateMap(ff, emptyMap())
            for (map in this@joinMaps) {
                map.entries().forEach { (s, p) ->
                    result.setOrUnion(s, p)
                }
            }
            return result
        }
    }

    private inline fun <R> List<Channel<T>>.runOn(action: Channel<T>.() -> R) = this.map { it.run(action) }
    private inline fun <Q, R> List<Pair<Channel<T>, Q>>.mapRunOn(action: Channel<T>.(Q) -> R) = this.map { (c, i) -> c.run { action(i) } }

    private fun List<Operator<T>>.computeAll(): List<StateMap<T>> = this.map { op ->
        executor.submit<StateMap<T>> {
            op.compute()
        }
    }.map { it.get() }

}