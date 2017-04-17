package com.github.sybila

import com.github.sybila.checker.Model
import com.github.sybila.checker.StateMap

/**
 * Heuristics algorithm:
 *
 * - For each state, create two Count objects and put all successor/predecessor colors in it.
 * - Do not count self-loops.
 * - Then intersect successors and predecessors on each count combination and save it into the appropriate count:
 *
 * for (i in predecessorCounts) {
 *      for (j in successorCounts) {
 *          if (i >= j) {
 *              magic[i-j] += predecessors[i] and successors[j]
 *          }
 *      }
 * }
 *
 * When selecting best state, choose according to highest magic count and highest parameter weight, with magic
 * getting priority. So always choose state with higher magic or one with the same magic and bigger weight.
 *
 */

class Heuristics(
        private val model: Model<Params>
) {

    data class MagicResult(
            val state: Int,
            val params: Params,
            val magic: Int,
            val magicWeight: Double
    )

    private val magics = Array<List<Params>>(model.stateCount) { state ->
        model.run {
            fun MutableList<Params>.setOrUnion(index: Int, value: Params) {
                while (this.size <= index) add(ff)
                this[index] = this[index] or value
            }
            val successors = state.successors(true).asSequence().fold(Count(model)) { count, t ->
                if (t.target != state) {
                    count.push(t.bound)
                }
                count
            }
            val predecessors = state.predecessors(true).asSequence().fold(Count(model)) { count, t ->
                if (t.target != state) {
                    count.push(t.bound)
                }
                count
            }
            val result = ArrayList<Params>()
            for (pI in (0 until predecessors.size)) {
                val p = predecessors[pI]
                for (sI in (0 until successors.size)) {
                    val s = successors[sI]
                    if (pI >= sI) {
                        val k = p and s
                        if (k.isSat()) {
                            result.setOrUnion(pI - sI, k)
                        }
                    }
                }
            }
            /*if (result.size == 5) {
                println("$state magic size: ${result.size} ${result.map { it.weight() }}")
            }*/
            //if (result.isEmpty()) println("$state empty!")
            result
        }
    }

    fun findMagic(universe: StateMap<Params>, uncovered: Params): MagicResult {
        var result = MagicResult(-1, model.ff, -1, 0.0)
        for ((state, params) in universe.entries()) {
            result = result.fight(state, params, uncovered)
        }
        return result
    }

    private fun MagicResult.fight(state: Int, params: Params, uncovered: Params): MagicResult {
        val result = this
        model.run {
            val stateMagic = magics[state]
            //println("Check $params")
            for (m in (stateMagic.indices.reversed())) {
                // if magic of this state is smaller, we don't even have to try...
                if (m < result.magic) return result
                val stateParams = params and uncovered
                val magic = stateParams and stateMagic[m]
                //println("For magic $m ${stateMagic[m]}")
                if (magic.isSat() && (m > result.magic || magic.weight() > result.magicWeight)) {
                    return MagicResult(state, stateParams, m, magic.weight())
                }
            }
            if (result.magic < 0) {
                return MagicResult(state, params and uncovered, -1, 0.0)
            }
        }
        return result
    }

    fun MagicResult.fight(other: MagicResult): MagicResult {
        return if (this.magic > other.magic) {
            this
        } else if (this.magic < other.magic) {
            other
        } else if (this.magicWeight >= other.magicWeight) {
            this
        } else {
            // this.magic == other.magic && this.magicWeight < other.magicWeight
            other
        }
    }

}