package com.github.sybila

import com.github.sybila.checker.Solver
import java.util.*

class Count<T: Any>(private val solver: Solver<T>) {

    private var data = ArrayList<T>().apply {
        this.add(solver.tt)
    }

    val size: Int
        get() {
            synchronized(this) {
                return data.size
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
                this@Count.data = new
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