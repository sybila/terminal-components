package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.CheckerStats
import com.github.sybila.checker.Partition
import java.util.*
import java.util.concurrent.CyclicBarrier

class NoCopyChannel(
        partition: Partition<Params>,
        private val barrier: CyclicBarrier,
        private val channels: Array<Array<List<Pair<Int, Params>>?>>
        ) : Channel<Params>, Partition<Params> by partition {

    companion object {
        fun connect(list: List<Partition<Params>>): List<Channel<Params>> {
            val barrier = CyclicBarrier(list.size)
            val channels = Array(list.size) { arrayOfNulls<List<Pair<Int, Params>>>(list.size) }
            return list.map { NoCopyChannel(it, barrier, channels) }
        }
    }

    override fun mapReduce(outgoing: Array<List<Pair<Int, Params>>?>): List<Pair<Int, Params>>? {
        CheckerStats.mapReduce(outgoing.sumBy { it?.size ?: 0 }.toLong())
        barrier.await()
        // everyone has a prepared transmission, now everyone will write it into channels
        outgoing.forEachIndexed { i, list ->
            channels[i][partitionId] = list
        }
        barrier.await()
        // all transmissions are in place. Check if somebody sent something and then collect your data
        val done = channels.all { it.all { it == null } }
        val result: List<Pair<Int, Params>>?
        if (!done) {
            val r = ArrayList<Pair<Int, Params>>()
            result = r
            channels[partitionId].forEach {
                it?.let { list ->
                    r.addAll(list)
                }
            }
        } else {
            result = null
        }
        barrier.await()
        // everyone has a result, so we can recycle buffers and clear channels
        channels[partitionId].indices.forEach { channels[partitionId][it] = null }
        return result
    }
}

fun List<Partition<Params>>.connectWithNoCopy(): List<Channel<Params>>
        = NoCopyChannel.connect(this)