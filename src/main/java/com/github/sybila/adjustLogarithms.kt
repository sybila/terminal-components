package com.github.sybila

import com.github.sybila.ode.generator.rect.Rectangle
import com.google.gson.Gson
import java.io.File
/*
fun main(args: Array<String>) {
    val gson = Gson()
    val data = File("/Users/daemontus/Downloads/clark_Vcell_15_17.json").readText()
    val result = gson.fromJson<ResultSet>(data, ResultSet::class.java)
    val normalizedResult = result.copy(parameterValues = result.parameterValues.map {
        // List of intervals
        val params = it as List<List<List<Double>>>
        val newParams: MutableSet<Rectangle> = params.map {
            Rectangle(it.flatMap { it.map { normalize(it) } }.toDoubleArray())
        }.toMutableSet()
        newParams
    }, parameterBounds = result.parameterBounds.map {
        it.map { normalize(it) }
    })
    val output = File("/Users/daemontus/Downloads/clark_Vcell_15_17.norm.json")
    output.writeText(gson.toJson(normalizedResult))
}

private fun normalize(value: Double) = 1/value // Math.log10(value)
        */