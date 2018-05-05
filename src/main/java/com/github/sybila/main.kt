package com.github.sybila

import com.github.sybila.checker.CheckerStats
import com.github.sybila.checker.Solver
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.Transition
import com.github.sybila.checker.map.RangeStateMap
import com.github.sybila.checker.solver.SolverStats
import com.github.sybila.ode.generator.AbstractOdeFragment
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import com.google.gson.Gson
import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import java.io.Closeable

internal abstract class Algorithm<T: Any, S: ExplicitOdeFragment<T, S>>(
        protected val config: Config,
        protected val allStates: S,
        protected val odeModel: OdeModel
) : Closeable {
    abstract fun computeComponents(): ResultSet
}

internal interface PivotChooser<T: Any> {
    fun choose(universe: StateMap<T>): StateMap<T>
}

fun main(args: Array<String>) {
    Config().apply {
        try {
            val parser = CmdLineParser(this)
            parser.parseArgument(*args)

            if (this.help) {
                parser.printUsage(System.err)
                System.err.println()
                return
            }

        } catch (e: CmdLineException) {
            // if there's a problem in the command line,
            // you'll get this exception. this will report
            // an error message.
            System.err.println(e.message)
            System.err.println("terminal-components [options...]")
            // print the list of available options (with fresh defaults)
            CmdLineParser(Config()).printUsage(System.err)
            System.err.println()
            return
        }
    }.use { config ->
        val log = config.logStream
        log?.println("Loading model and computing approximation...")

        SolverStats.reset(log)
        CheckerStats.reset(log)

        val modelFile = config.model ?: error("Missing model file.")
        val odeModel = Parser().parse(modelFile).computeApproximation(
                fast = config.fastApproximation, cutToRange = config.cutToRange
        )

        log?.println("Computing transition system...")

        val result = odeModel.run {
            val isRectangular = variables.all {
                it.equation.map { it.paramIndex }.filter { it >= 0 }.toSet().size <= 1
            }
            if (!isRectangular) error("Multiple parameters in one equation detected. This situation is not supported.")

            //when {
                /* This seems to be useless with regards to speed :/
                parameters.size == 1 -> {
                    log?.println("Using interval solver.")
                    IntervalSetOdeModel(this, !config.disableSelfLoops)
                            .makeExplicit(config, { a, b, c, d -> ExplicitOdeFragment.Interval(a,b,c,d) }).also {
                                log?.println("Start component analysis...")
                            }.runAnalysis(odeModel, config)
                }
                parameters.size == 2 -> {
                    log?.println("Using grid solver.")
                    RectangleSetOdeModel(this, !config.disableSelfLoops)
                            .makeExplicit(config, { a, b, c, d -> ExplicitOdeFragment.Grid(a,b,c,d) }).also {
                                log?.println("Start component analysis...")
                            }.runAnalysis(odeModel, config)
                }*/
                //else -> {
                    log?.println("Using generic rectangular solver.")
                    RectangleOdeModel(this, !config.disableSelfLoops)
                            .makeExplicit(config, { a, b, c, d, e -> ExplicitOdeFragment.Rectangular(a, b, c, d, e) }).also {
                                log?.println("Start component analysis...")
                            }.runAnalysis(odeModel, config)
                //}
            //}
        }

        log?.println("Attractor analysis finished, printing results...")
        log?.println("Found attractor counts: ${result.results.map { it.formula }}")

        SolverStats.printGlobal()
        CheckerStats.printGlobal()

        val json = Gson()
        config.resultStream?.println(json.toJson(result))
    }
}

internal inline fun <T: Any, S: ExplicitOdeFragment<T, S>> AbstractOdeFragment<T>.makeExplicit(
        config: Config,
        constructor: (Solver<T>, Int, StateMap<T>, Array<List<Transition<T>>>, Array<List<Transition<T>>>) -> S
): S {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    return constructor(this.solver, stateCount, RangeStateMap(0 until stateCount, tt, ff), successors, predecessors)
}

private fun <T: Any, S: ExplicitOdeFragment<T, S>> S.runAnalysis(odeModel: OdeModel, config: Config): ResultSet {
    val algorithm = LocalAlgorithm(config, this, odeModel)
    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { println("Search elapsed: ${System.currentTimeMillis() - start}ms") }
    }
}