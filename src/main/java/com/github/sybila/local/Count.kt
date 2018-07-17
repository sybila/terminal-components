package com.github.sybila.local

import java.util.ArrayList

class Count<T>(private val solver: Solver<T>) {

    private var data: List<T> = ArrayList<T>().apply {
        this.add(solver.fullSet)
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
                return data.indexOfLast { !solver.isEmpty(it) } + 1
            }
        }


    val min: Int
        get() {
            synchronized(this) {
                return data.indexOfFirst { !solver.isEmpty(it) } + 1
            }
        }

    private val default = solver.emptySet

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
                    addOrUnion(new, i, intersect(data[i], complement(params, fullSet)))
                    addOrUnion(new, i+1, intersect(data[i], params))
                }
                this@Count.data = new.dropLastWhile { isEmpty(it) }
            }
        }
    }

    private fun Solver<T>.addOrUnion(data: ArrayList<T>, index: Int, params: T) {
        if (index < data.size) {
            data[index] = union(data[index], params)
        } else {
            data.add(params)
        }
    }

}