package com.github.sybila

import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.operator.TrueOperator
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.bool.BoolOdeModel
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import java.io.File
import java.util.*
import kotlin.system.measureTimeMillis

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

    val odeModel = Parser().parse(modelFile).computeApproximation(fast = true, cutToRange = false)

    Algorithm(8, false).run {
        var count = 0
        val time = measureTimeMillis {
            odeModel.forEachParameter { model ->
                val transitionSystem = SingletonChannel(BoolOdeModel(model).asSingletonPartition())
                transitionSystem.run {
                    // counter is synchronized
                    val counter = Count(this)

                    // compute results
                    val allStates = TrueOperator(this)
                    startAction(allStates, counter)

                    //blockWhilePending()

                    count = Math.max(counter.size, count)
                }
            }
        }
        println("Count: $count in $time")
        executor.shutdown()
    }
}