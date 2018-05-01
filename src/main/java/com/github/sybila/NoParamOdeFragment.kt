package com.github.sybila

import com.github.sybila.checker.*
import com.github.sybila.huctl.*
import com.github.sybila.ode.generator.CutStateMap
import com.github.sybila.ode.generator.LazyStateMap
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleSolver
import com.github.sybila.ode.model.OdeModel
import java.net.InterfaceAddress
import java.util.ArrayList
import java.util.HashMap

abstract class NoParamOdeFragment(
        protected val model: OdeModel,
        private val createSelfLoops: Boolean
) : Model<Params>, Solver<Params> by RectangleSolver(Rectangle(doubleArrayOf()))  {

    protected val encoder = NodeEncoder(model)
    protected val dimensions = model.variables.size

    init {
        if (model.parameters.isNotEmpty()) error("Only models without parameters are supported!")
        if (dimensions >= 30) throw IllegalStateException("Too many dimensions! Max. supported: 30")
    }

    override val stateCount: Int = encoder.stateCount

    /**
     * Return params for which dimensions derivation at vertex is positive/negative.
     *
     * Return null if there are no such parameters.
     */
    abstract fun isVertexValid(vertex: Int, dimension: Int, positive: Boolean): Boolean

    //Facet param cache.
    //(a,b) -> P <=> p \in P: a -p-> b
    //private val facetColors = arrayOfNulls<Any>(stateCount * dimensions * 4)//HashMap<FacetId, Params>(stateCount * dimensions * 4)

    private val PositiveIn = 0
    private val PositiveOut = 1
    private val NegativeIn = 2
    private val NegativeOut = 3

    private fun facetIndex(from: Int, dimension: Int, orientation: Int)
            = from + (stateCount * dimension) + (stateCount * dimensions * orientation)

    private fun isFacetValid(from: Int, dimension: Int, orientation: Int): Boolean {
        //iterate over vertices
        val positiveFacet = if (orientation == PositiveIn || orientation == PositiveOut) 1 else 0
        val positiveDerivation = orientation == PositiveOut || orientation == NegativeIn

        return vertexMasks.asSequence()
                .filter { it.shr(dimension).and(1) == positiveFacet }
                .fold(false) { a, mask ->
                    val vertex = encoder.nodeVertex(from, mask)
                    a || isVertexValid(vertex, dimension, positiveDerivation)
                }
    }

    //enumerate all bit masks corresponding to vertices of a state
    private val vertexMasks: IntArray = (0 until dimensions).fold(listOf(0)) { a, d ->
        a.map { it.shl(1) }.flatMap { listOf(it, it.or(1)) }
    }.toIntArray()

    /*** PROPOSITION RESOLVING ***/


    override fun Formula.Atom.Float.eval(): StateMap<Params> {
        val left = this.left
        val right = this.right
        val dimension: Int
        val threshold: Int
        val gt: Boolean
        when {
            left is Expression.Variable && right is Expression.Constant -> {
                dimension = model.variables.indexOfFirst { it.name == left.name }
                if (dimension < 0) throw IllegalArgumentException("Unknown variable ${left.name}")
                threshold = model.variables[dimension].thresholds.indexOfFirst { it == right.value }
                if (threshold < 0) throw IllegalArgumentException("Unknown threshold ${right.value}")

                gt = when (this.cmp) {
                    CompareOp.EQ, CompareOp.NEQ -> throw IllegalArgumentException("${this.cmp} comparison not supported.")
                    CompareOp.GT, CompareOp.GE -> true
                    CompareOp.LT, CompareOp.LE -> false
                }
            }
            left is Expression.Constant && right is Expression.Variable -> {
                dimension = model.variables.indexOfFirst { it.name == right.name }
                if (dimension < 0) throw IllegalArgumentException("Unknown variable ${right.name}")
                threshold = model.variables[dimension].thresholds.indexOfFirst { it == left.value }
                if (threshold < 0) throw IllegalArgumentException("Unknown threshold ${left.value}")

                gt = when (this.cmp) {
                    CompareOp.EQ, CompareOp.NEQ -> throw IllegalArgumentException("${this.cmp} comparison not supported.")
                    CompareOp.GT, CompareOp.GE -> false
                    CompareOp.LT, CompareOp.LE -> true
                }
            }
            else -> throw IllegalAccessException("Proposition is too complex: ${this}")
        }
        val dimensionSize = model.variables[dimension].thresholds.size - 1
        return CutStateMap(
                encoder = encoder,
                dimension = dimension,
                threshold = threshold,
                gt = gt,
                stateCount = stateCount,
                value = tt,
                default = ff,
                sizeHint = if (gt) {
                    (stateCount / dimensionSize) * (dimensionSize - threshold + 1)
                } else {
                    (stateCount / dimensionSize) * (threshold - 1)
                }
        )
    }

    override fun Formula.Atom.Transition.eval(): StateMap<Params> {
        val dimension = model.variables.indexOfFirst { it.name == this.name }
        if (dimension < 0) throw IllegalStateException("Unknown variable name: ${this.name}")
        return LazyStateMap(stateCount, ff) {
            val c = if (isFacetValid(it, dimension, when {
                facet == Facet.POSITIVE && direction == Direction.IN -> PositiveIn
                facet == Facet.POSITIVE && direction == Direction.OUT -> PositiveOut
                facet == Facet.NEGATIVE && direction == Direction.IN -> NegativeIn
                else -> NegativeOut
            })) tt else ff
            val exists = (facet == Facet.POSITIVE && encoder.higherNode(it, dimension) != null)
                    || (facet == Facet.NEGATIVE && encoder.lowerNode(it, dimension) != null)
            if (exists && c.canSat()) c else null
        }
    }

    /*** Successor/Predecessor resolving ***/

    private val successorCache = HashMap<Int, IntArray>(encoder.stateCount)
    private val pastSuccessorCache = HashMap<Int, IntArray>(encoder.stateCount)
    private val predecessorCache = HashMap<Int, IntArray>(encoder.stateCount)
    private val pastPredecessorCache = HashMap<Int, IntArray>(encoder.stateCount)

    override fun Int.predecessors(timeFlow: Boolean): Iterator<Transition<Params>>
            = getStep(this, timeFlow, false).iterator()

    override fun Int.successors(timeFlow: Boolean): Iterator<Transition<Params>>
            = getStep(this, timeFlow, true).iterator()

    val transioions = Array(encoder.stateCount) { i -> Transition(i, DirectionFormula.Atom.True, tt) }

    private fun getStep(from: Int, timeFlow: Boolean, successors: Boolean): List<Transition<Params>> {
        val array: IntArray = (when {
            timeFlow && successors -> successorCache
            timeFlow && !successors -> predecessorCache
            !timeFlow && successors -> pastSuccessorCache
            else -> pastPredecessorCache
        }).computeIfAbsent(from) {
            val result = ArrayList<Int>()
            //selfLoop <=> !positiveFlow && !negativeFlow <=> !(positiveFlow || negativeFlow)
            //positiveFlow = (-in && +out) && !(-out || +In) <=> -in && +out && !-out && !+In
            var selfloop = true
            for (dim in model.variables.indices) {

                val positiveOut = isFacetValid(from, dim, if (timeFlow) PositiveOut else PositiveIn)
                val positiveIn = isFacetValid(from, dim, if (timeFlow) PositiveIn else PositiveOut)
                val negativeOut = isFacetValid(from, dim, if (timeFlow) NegativeOut else NegativeIn)
                val negativeIn = isFacetValid(from, dim, if (timeFlow) NegativeIn else NegativeOut)

                encoder.higherNode(from, dim)?.let { higher ->
                    val colors = (if (successors) positiveOut else positiveIn)
                    if (colors) result.add(higher)

                    if (createSelfLoops) {
                        val positiveFlow = negativeIn && positiveOut && !(negativeOut || positiveIn)
                        selfloop = selfloop && !positiveFlow
                    }
                }

                encoder.lowerNode(from, dim)?.let { lower ->
                    val colors = (if (successors) negativeOut else negativeIn)
                    if (colors) result.add(lower)

                    if (createSelfLoops) {
                        val negativeFlow = negativeOut && positiveIn && !(negativeIn || positiveOut)
                        selfloop = selfloop && !negativeFlow
                    }
                }

            }

            if (selfloop) result.add(from)

            result.toIntArray()
        }

        return array.map {
            transioions[it]
        }
    }

}