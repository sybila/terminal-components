package com.github.sybila

import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.bool.BoolOdeModel
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import java.io.File
import java.util.*

fun OdeModel.forEachParameter(action: (OdeModel) -> Unit) {

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

    fun substitute(model: OdeModel, valuations: List<DoubleArray>, action: (OdeModel) -> Unit) {
        if (model.parameters.isEmpty()) action(model)
        else {
            for (value in valuations.last()) {
                if (value == 0.0) continue
                substitute(
                        model.substituteLastParameter(value),
                        valuations.dropLast(1), action
                )
            }
        }
    }

    substitute(this, valuations, action)
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

    val states = Array<List<Int>>(f.stateCount) { emptyList() }

    val tarjan = Tarjan()

    var max = 0

    odeModel.forEachParameter { model ->
        BoolOdeModel(model).run {
            // create state space
            for (s in 0 until stateCount) {
                states[s] = s.successors(true).asSequence().map { it.target }.toList()
            }

            val result = tarjan.getSCComponents(states)
            if (result in (max + 1)..9) {
                max = result
            }
        }
    }

    println("Done")
    //println("Found max $max components")
}