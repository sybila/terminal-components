package com.github.sybila

import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import java.io.File
import java.util.*

fun OdeModel.forEachParameter(action: () -> Unit) {

    val stateCoder = NodeEncoder(this)

    val valuations: List<DoubleArray> = (this.parameters.indices).map { pIndex ->
        val range = this.parameters[pIndex].range
        val result = hashSetOf(range.first, range.second)
        for ((_, _, _, _, eq) in this.variables) {
            // WARNING: Here we assume that the model is rectangular, otherwise this is not correct...
            val param = eq.firstOrNull { it.hasParam() }?.paramIndex
            if (param == pIndex) {
                for (vertex in 0 until stateCoder.vertexCount) {
                    var derivationValue = 0.0
                    var denominator = 0.0

                    //evaluate equations
                    for (summand in eq) {
                        var partialSum = summand.constant
                        for (v in summand.variableIndices) {
                            partialSum *= this.variables[v].thresholds[stateCoder.vertexCoordinate(vertex, v)]
                        }
                        if (partialSum != 0.0) {
                            for (function in summand.evaluable) {
                                val index = function.varIndex
                                partialSum *= function(this.variables[index].thresholds[stateCoder.vertexCoordinate(vertex, index)])
                            }
                        }
                        if (summand.hasParam()) {
                            denominator += partialSum
                        } else {
                            derivationValue += partialSum
                        }
                    }

                    if (denominator != 0.0) {
                        val split = -derivationValue / denominator
                        if (split > range.first && split < range.second) {
                            result.add(split)
                        }
                    }
                }
            }
        }

        val resultArray = result.toDoubleArray()
        Arrays.sort(resultArray)
        resultArray
    }

    fun substitute(remaining: Int, valuations: List<DoubleArray>, action: () -> Unit) {
        if (remaining == 0) action()
        else {
            for (value in valuations.last()) {
                if (value == 0.0) continue
                substitute(
                        remaining - 1,
                        valuations.dropLast(1), action
                )
            }
        }
    }

    substitute(this.parameters.size, valuations, action)
}

fun OdeModel.substituteLastParameter(value: Double): OdeModel = this.copy(
        variables = variables.map { v ->
            v.copy(equation = v.equation.map { summand ->
                if (summand.paramIndex == this.parameters.lastIndex) {
                    summand.copy(
                            paramIndex = -1,
                            constant = summand.constant * value
                    )
                } else summand
            })
        },
        parameters = parameters.dropLast(1)
)

fun main(args: Array<String>) {
    val modelFile = File(args[0])

    val odeModel = Parser().parse(modelFile).computeApproximation(fast = false, cutToRange = false)

    val f = RectangleOdeModel(odeModel)

    println("Start counting...")

    var count = 0L
    odeModel.forEachParameter {
        count += 1
    }

    println("Done. Valuation count: $count")
    //println("Found max $max components")
}