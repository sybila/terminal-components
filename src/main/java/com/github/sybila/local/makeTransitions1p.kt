package com.github.sybila.local

import com.github.sybila.local.solver.IntervalSolver
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import com.github.sybila.ode.model.toBio
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val processes = 4//Runtime.getRuntime().availableProcessors() + 1
private val executor = Executors.newFixedThreadPool(processes)

fun main(args: Array<String>) {
    val start = System.currentTimeMillis()
    val model = Parser().parse(File("/Users/daemontus/Papers/brim_icpads/clark_Sam_fast.bio"))
            .computeApproximation(cutToRange = true)
    if (model.parameters.size != 1) error("Supports only one parameter.")
    val parameter = model.parameters.first()
    val solver = IntervalSolver(parameter.range.first, parameter.range.second)
    val ts = makeTS(model, solver)

    File("model.bio").writeText(model.toBio())
    DataOutputStream(File("ts.bin").outputStream().buffered()).use {
        ts.write(it, IntSerializer, solver)
    }

    println("Computed in ${System.currentTimeMillis() - start}")
    executor.shutdown()
}

private inline fun <T> fillArrayParallel(array: Array<T>, crossinline action: (Int) -> T): Array<T> {
    val chunk = (array.size / processes) + 1
    (0 until processes).map { pid ->
        executor.submit {
            for (i in (pid*chunk) until Math.min((pid+1)*chunk, array.size)) {
                array[i] = action(i)
            }
        }
    }.map { it.get() }
    return array
}

private const val PositiveIn = 0
private const val PositiveOut = 1
private const val NegativeIn = 2
private const val NegativeOut = 3

private fun makeTS(model: OdeModel, solver: Solver<DoubleArray>): ExplicitTransitionSystem<Int, DoubleArray> {
    var start = System.currentTimeMillis()
    val dimensions = model.variables.size
    val encoder = NodeEncoder(model)
    val stateCount = encoder.stateCount
    //println("Compute positive vertices...")
    val positiveVertex = Array(dimensions) { dim ->
        fillArrayParallel<DoubleArray?>(Array(encoder.vertexCount) { solver.emptySet }) { vertex ->
            getVertexValue(model, encoder, solver, vertex, dim, true)
        }
    }
    //println("Compute negative vertices...")
    val negativeVertex = Array(dimensions) { dim ->
        fillArrayParallel<DoubleArray?>(Array(encoder.vertexCount) { solver.emptySet }) { vertex ->
            getVertexValue(model, encoder, solver, vertex, dim, false)
        }
    }

    println("Vertices computed in ${System.currentTimeMillis() - start}")

    val vertexMasks: IntArray = (0 until dimensions).fold(listOf(0)) { a, _ ->
        a.map { it.shl(1) }.flatMap { listOf(it, it.or(1)) }
    }.toIntArray()

    start = System.currentTimeMillis()
    //println("Compute facet colors...")
    val facetColors = Array(4) { orientation ->
        Array(dimensions) { dimension ->
            fillArrayParallel(Array<DoubleArray?>(stateCount) { null }) { from ->
                //iterate over vertices
                val positiveFacet = if (orientation == PositiveIn || orientation == PositiveOut) 1 else 0
                val positiveDerivation = orientation == PositiveOut || orientation == NegativeIn
                vertexMasks
                        .filter { it.shr(dimension).and(1) == positiveFacet }
                        .fold(solver.emptySet) { a, mask ->
                            val vertex = encoder.nodeVertex(from, mask)
                            val vertexColor = if (positiveDerivation) {
                                positiveVertex[dimension][vertex] ?: solver.emptySet
                            } else {
                                negativeVertex[dimension][vertex] ?: solver.emptySet
                            }
                            solver.union(a, vertexColor)
                        }
            }
        }
    }

    println("Facets computed in ${System.currentTimeMillis() - start}")

    start = System.currentTimeMillis()
    val states = ConcurrentHashMap<Int, DoubleArray>(stateCount)
    val edges = ConcurrentHashMap<Pair<Int, Int>, DoubleArray>(stateCount * dimensions * 4)
    val successors = ConcurrentHashMap<Int, List<Int>>(stateCount)
    val predecessors = ConcurrentHashMap<Int, MutableList<Int>>(stateCount)
    val chunk = (stateCount / processes) + 1
    (0 until processes).map { pid ->
        executor.submit {
            for (s in (pid*chunk) until Math.min(stateCount, (pid+1)*chunk)) {
                states[s] = solver.fullSet
                // compute successors
                val next = solver.getSuccessors(s, model, encoder, facetColors)
                successors[s] = next.map { it.first }
                next.forEach { (t, p) ->
                    if (p != null) {
                        edges[s to t] = p
                        predecessors.compute(t) { _, v ->
                            if (v == null) mutableListOf(s) else {
                                v.add(s)
                                v
                            }
                        }
                    }
                }
            }
        }
    }.map { it.get() }
    println("Transitions computed in ${System.currentTimeMillis() - start}")

    return ExplicitTransitionSystem(
            solver, states, edges, successors, predecessors
    )
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Solver<DoubleArray>.getSuccessors(from: Int, model: OdeModel, encoder: NodeEncoder, facetColors: Array<Array<Array<DoubleArray?>>>): List<Pair<Int, DoubleArray?>> {
    val result = ArrayList<Pair<Int, DoubleArray?>>()
    //selfLoop <=> !positiveFlow && !negativeFlow <=> !(positiveFlow || negativeFlow)
    //positiveFlow = (-in && +out) && !(-out || +In) <=> -in && +out && !-out && !+In
    var selfloop = fullSet
    for (dim in model.variables.indices) {
        val positiveOut = facetColors[PositiveOut][dim][from] ?: emptySet
        val positiveIn = facetColors[PositiveIn][dim][from] ?: emptySet
        val negativeOut = facetColors[NegativeOut][dim][from] ?: emptySet
        val negativeIn = facetColors[NegativeIn][dim][from] ?: emptySet

        encoder.higherNode(from, dim)?.let { higher ->
            if (!isEmpty(positiveOut)) {
                result.add(higher to positiveOut)
            }

            val positiveFlow = intersect(negativeIn, intersect(positiveOut, complement(union(negativeOut, positiveIn), fullSet)))
            selfloop = intersect(selfloop, complement(positiveFlow, fullSet))
        }

        encoder.lowerNode(from, dim)?.let { lower ->
            if (!isEmpty(negativeOut)) {
                result.add(lower to negativeOut)
            }

            val negativeFlow = intersect(negativeOut, intersect(positiveIn, complement(union(negativeIn, positiveOut), fullSet)))
            selfloop = intersect(selfloop, complement(negativeFlow, fullSet))
        }
    }

    if (!isEmpty(selfloop)) {
        result.add(from to selfloop)
    }

    return result
}

@Suppress("NOTHING_TO_INLINE")
private inline fun getVertexValue(model: OdeModel, encoder: NodeEncoder, solver: Solver<DoubleArray>, vertex: Int, dim: Int, positive: Boolean): DoubleArray? {
    var derivationValue = 0.0
    var denominator = 0.0
    var parameterIndex = -1

    //evaluate equations
    for (summand in model.variables[dim].equation) {
        var partialSum = summand.constant
        for (v in summand.variableIndices) {
            partialSum *= model.variables[v].thresholds[encoder.vertexCoordinate(vertex, v)]
        }
        if (partialSum != 0.0) {
            for (function in summand.evaluable) {
                val index = function.varIndex
                partialSum *= function(model.variables[index].thresholds[encoder.vertexCoordinate(vertex, index)])
            }
        }
        if (summand.hasParam()) {
            parameterIndex = summand.paramIndex
            denominator += partialSum
        } else {
            derivationValue += partialSum
        }
    }

    return if (parameterIndex == -1 || denominator == 0.0) {
        if ((derivationValue > 0 && positive) || (derivationValue < 0 && !positive)) solver.fullSet else solver.emptySet
    } else {
        //if you divide by negative number, you have to flip the condition
        val newPositive = if (denominator > 0) positive else !positive
        val range = solver.fullSet
        //min <= split <= max
        val split = Math.min(range[1], Math.max(range[0], -derivationValue / denominator))
        val newLow = if (newPositive) split else range[0]
        val newHigh = if (newPositive) range[1] else split

        if (newLow >= newHigh) null else doubleArrayOf(newLow, newHigh)
    }
}