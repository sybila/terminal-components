package com.github.sybila.local

import com.github.sybila.ResultSet
import com.github.sybila.exportResults
import com.github.sybila.local.solver.IntervalSolver
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import com.google.gson.Gson
import java.io.File

fun main(args: Array<String>) {
    val model = Parser().parse(File("/Users/daemontus/Papers/brim_icpads/clark_Sam.bio"))
            .computeApproximation(cutToRange = true)
    if (model.parameters.size != 1) error("Supports only one parameter.")
    val parameter = model.parameters.first()
    val solver = IntervalSolver(parameter.range.first, parameter.range.second)
    val ts = makeTS(model, solver)

    val start = System.currentTimeMillis()
    println("Start algorithm")
    val alg = Algorithm(solver, ts)
    val result: ResultSet = exportResults(
            model,
            alg.execute().also {
                println("Computed in: ${System.currentTimeMillis() - start}")
            }.mapIndexed { i, map -> "$i attractor(s)" to listOf(map) }.toMap()
    )

    /*val json = Gson()
    File("result.json").writeText(json.toJson(result))*/
}

private const val PositiveIn = 0
private const val PositiveOut = 1
private const val NegativeIn = 2
private const val NegativeOut = 3

private fun makeTS(model: OdeModel, solver: Solver<DoubleArray>): TransitionSystem<Int, DoubleArray> {
    val dimensions = model.variables.size
    val encoder = NodeEncoder(model)
    val stateCount = encoder.stateCount
    println("Compute positive vertices...")
    val positiveVertex = Array(encoder.vertexCount) { vertex ->
        Array(dimensions) { dim ->
            getVertexValue(model, encoder, solver, vertex, dim, true)
        }
    }
    println("Compute negative vertices...")
    val negativeVertex = Array(encoder.vertexCount) { vertex ->
        Array(dimensions) { dim ->
            getVertexValue(model, encoder, solver, vertex, dim, false)
        }
    }

    val vertexMasks: IntArray = (0 until dimensions).fold(listOf(0)) { a, _ ->
        a.map { it.shl(1) }.flatMap { listOf(it, it.or(1)) }
    }.toIntArray()

    val facetColors = Array(4) { orientation ->
        Array(dimensions) { dimension ->
            Array(stateCount) { from ->
                //iterate over vertices
                val positiveFacet = if (orientation == PositiveIn || orientation == PositiveOut) 1 else 0
                val positiveDerivation = orientation == PositiveOut || orientation == NegativeIn
                vertexMasks
                        .filter { it.shr(dimension).and(1) == positiveFacet }
                        .fold(solver.emptySet) { a, mask ->
                            val vertex = encoder.nodeVertex(from, mask)
                            val vertexColor = if (positiveDerivation) {
                                positiveVertex[vertex][dimension] ?: solver.emptySet
                            } else {
                                negativeVertex[vertex][dimension] ?: solver.emptySet
                            }
                            solver.union(a, vertexColor)
                        }
            }
        }
    }

    val states = HashMap<Int, DoubleArray>(stateCount)
    val edges = HashMap<Pair<Int, Int>, DoubleArray>(stateCount * dimensions * 4)
    val successors = HashMap<Int, List<Int>>(stateCount)
    val predecessors = HashMap<Int, MutableList<Int>>(stateCount)
    for (s in 0 until stateCount) {
        if (s-1 % 100000 == 0) println("Generate: $s/$stateCount")
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

    return ExplicitTransitionSystem(
            solver, states, edges, successors, predecessors
    )
}

@Suppress("NOTHING_TO_INLINE")
private inline fun Solver<DoubleArray>.getSuccessors(from: Int, model: OdeModel, encoder: NodeEncoder, facetColors: Array<Array<Array<DoubleArray>>>): List<Pair<Int, DoubleArray?>> {
    val result = ArrayList<Pair<Int, DoubleArray?>>()
    //selfLoop <=> !positiveFlow && !negativeFlow <=> !(positiveFlow || negativeFlow)
    //positiveFlow = (-in && +out) && !(-out || +In) <=> -in && +out && !-out && !+In
    var selfloop = fullSet
    for (dim in model.variables.indices) {
        val dimName = model.variables[dim].name
        val positiveOut = facetColors[PositiveOut][dim][from]
        val positiveIn = facetColors[PositiveIn][dim][from]
        val negativeOut = facetColors[NegativeOut][dim][from]
        val negativeIn = facetColors[NegativeIn][dim][from]

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

private fun MutableSet<Rectangle>.makeIntervalSet(): DoubleArray =
        this.map { it.asIntervals().first() }.sortedBy { it[0] }.flatMap { it.toList() }.toDoubleArray()