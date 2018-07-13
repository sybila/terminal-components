package com.github.sybila

import com.google.gson.Gson
import java.io.File

fun main(args: Array<String>) {
    val gson = Gson()
    val data = File("/Users/daemontus/Downloads/clark_pH_carb_0_14.norm.json").readText()
    val result = gson.fromJson<ResultSet>(data, ResultSet::class.java)
    for (formula in result.results) {
        println("Check ${formula.formula}:")
        val stateIndices = formula.data.map { it.first() }
        val states = stateIndices.map { result.states[it] }
        val totalVolume = states.map { it.volume() }.sum()
        for (i in result.variables.indices) {
            println("Histogram for variable ${result.variables[i]}")
            val t = result.thresholds[i]
            val histo = ArrayList<Double>()
            val count = ArrayList<Int>()
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY
            for ((l, h) in t.dropLast(1).zip(t.drop(1))) {
                val states = states.filter { it.bounds[i][0] == l && it.bounds[i][1] == h }
                count.add(states.size)
                if (states.isNotEmpty()) {
                    if (l < min) min = l
                    if (h > max) max = h
                }
                histo.add(states.map { it.volume() }.sum() / totalVolume)
            }
            println("Bounds: [$min, $max]")
            println(count)
            println(histo)
        }
    }
}

private fun State.volume(): Double {
    val interval = bounds.map { it[1] - it[0] }
    return interval.drop(1).fold(interval[0]) { a, b -> a * b }
}