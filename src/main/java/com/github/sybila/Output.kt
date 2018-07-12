package com.github.sybila

import com.github.sybila.checker.StateMap
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.OdeModel
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.util.*

// NOTE: These classes are taken directly from the pithya core printer.
// They are not included as a dependency, because they are, as of now,
// not yet a stable part of the API.

internal fun Int.prettyPrint(model: OdeModel, encoder: NodeEncoder): String = "State($this)${
model.variables.map { it.thresholds }.mapIndexed { dim, thresholds ->
    val t = encoder.coordinate(this, dim)
    "[${thresholds[t]}, ${thresholds[t+1]}]"
}.joinToString()}"

internal fun Int.expand(model: OdeModel, encoder: NodeEncoder): State {
    return State(id = this.toLong(), bounds = model.variables.mapIndexed { i, variable ->
        val c = encoder.coordinate(this, i)
        listOf(variable.thresholds[c], variable.thresholds[c+1])
    })
}

internal data class ResultSet(
        val variables: List<String>,
        val parameters: List<String>,
        val thresholds: List<List<Double>>,
        val states: List<State>,
        val type: String,
        @SerializedName("parameter_values")
        val parameterValues: List<Any>,
        @SerializedName("parameter_bounds")
        val parameterBounds: List<List<Double>>,
        val results: List<Result>
)

internal class Result(
        val formula: String,
        val data: List<List<Int>>
)

internal class State(
        val id: Long,
        val bounds: List<List<Double>>
)


internal fun printJsonRectResults(model: OdeModel, result: Map<String, List<StateMap<Set<Rectangle>>>>): String {
    val stateIndexMapping = HashMap<Int, Int>()
    val states = ArrayList<Int>()
    val paramsIndexMapping = HashMap<Set<Rectangle>, Int>()
    val params = ArrayList<Set<Rectangle>>()
    val map = ArrayList<Result>()
    for ((f, r) in result) {
        val rMap = ArrayList<List<Int>>()
        for (partitionResult in r) {
            for ((s, p) in partitionResult.entries()) {
                val stateIndex = stateIndexMapping.computeIfAbsent(s) {
                    states.add(s)
                    states.size - 1
                }
                val paramIndex = paramsIndexMapping.computeIfAbsent(p) {
                    params.add(p)
                    params.size - 1
                }
                rMap.add(listOf(stateIndex, paramIndex))
            }
        }
        map.add(Result(f, rMap))
    }
    val coder = NodeEncoder(model)
    val r = ResultSet(
            variables = model.variables.map { it.name },
            parameters = model.parameters.map { it.name },
            thresholds = model.variables.map { it.thresholds },
            states = states.map { it.expand(model, coder) },
            type = "rectangular",
            results = map,
            parameterValues = params.map {
                it.map { it.asIntervals() }
            },
            parameterBounds = model.parameters.map { listOf(it.range.first, it.range.second) }
    )

    return Gson().toJson(r)
}
