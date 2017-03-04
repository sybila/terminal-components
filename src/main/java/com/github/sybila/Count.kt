package com.github.sybila

import com.github.sybila.checker.Solver
import java.util.*

/**
 * Count is a special data structure that allows us to keep the number of components
 * for specific colors.
 */
class Count<T: Any>(private val solver: Solver<T>) {

    private var data: List<T> = ArrayList<T>().apply {
        this.add(solver.tt)
    }

    val size: Int
        get() {
            synchronized(this) {
                return data.size
            }
        }


    val max: Int
        get() {
            synchronized(this) {
                return data.indexOfLast { solver.run { it.isSat() } } + 1
            }
        }


    val min: Int
        get() {
            synchronized(this) {
                return data.indexOfFirst { solver.run { it.isSat() } } + 1
            }
        }

    private val default = solver.ff

    operator fun get(index: Int): T {
        synchronized(this) {
            return if (index < data.size) data[index] else default
        }
    }

    fun push(params: T) {
        synchronized(this) {
            solver.run {
                val new = ArrayList<T>()
                for (i in data.indices) {
                    addOrUnion(new, i, data[i] and params.not())
                    addOrUnion(new, i+1, data[i] and params)
                }
                this@Count.data = new.dropLastWhile { it.isNotSat() }
            }
        }
    }

    private fun Solver<T>.addOrUnion(data: ArrayList<T>, index: Int, params: T) {
        if (index < data.size) {
            data[index] = (data[index] or params)
        } else {
            data.add(params)
        }
    }

}