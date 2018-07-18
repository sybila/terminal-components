package com.github.sybila

import com.github.sybila.checker.CheckerStats
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

internal abstract class Algorithm<T: Any>(
        protected val config: Config,
        protected val allStates: ExplicitOdeFragment<T>,
        protected val odeModel: OdeModel
) : Closeable {
    abstract fun computeComponents(): ResultSet
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

        var start = 0L
        val result = odeModel.run {
            val isRectangular = variables.all {
                it.equation.map { it.paramIndex }.filter { it >= 0 }.toSet().size <= 1
            }

            when {
                !isRectangular -> error("Multiple parameters in one equation detected. This situation is not supported.")
                else -> {
                    log?.println("Using generic rectangular solver.")
                    RectangleOdeModel(this, !config.disableSelfLoops)
                            .makeExplicit(config).also {
                                log?.println("Start component analysis...")
                                start = System.currentTimeMillis()
                            }.runAnalysis(odeModel, config).also {
                                println("Analysis time: ${System.currentTimeMillis() - start}")
                            }
                }
            }
        }

        log?.println("Attractor analysis finished, printing results...")
        log?.println("Found attractor counts: ${result.results.map { it.formula }}")

        SolverStats.printGlobal()
        CheckerStats.printGlobal()

        val json = Gson()
        config.resultStream?.println(json.toJson(result))
    }
}

private fun <T: Any> AbstractOdeFragment<T>.makeExplicit(
        config: Config
): ExplicitOdeFragment<T> {
    val step = (stateCount / 100).coerceAtLeast(100)
    val successors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Successor progress: $s/$stateCount")
        s.successors(true).asSequence().toList()
    }
    val predecessors = Array(stateCount) { s ->
        if (s % step == 0) config.logStream?.println("Predecessor progress: $s/$stateCount")
        s.predecessors(true).asSequence().toList()
    }

    val pivotChooser: (ExplicitOdeFragment<T>) -> PivotChooser<T> = if (config.disableHeuristic) {
        { fragment -> NaivePivotChooser(fragment) }
    } else {
        { fragment -> StructureAndCardinalityPivotChooser(fragment) }
    }

    return ExplicitOdeFragment(this.solver, stateCount, pivotChooser, successors, predecessors)
}

private fun <T: Any> ExplicitOdeFragment<T>.runAnalysis(odeModel: OdeModel, config: Config): ResultSet {
    val algorithm = if (config.algorithm == Config.AlgorithmType.LOCAL) {
        LocalAlgorithm(config, this, odeModel)
    } else {
        DistAlgorithm(config, this, odeModel)
    }

    val start = System.currentTimeMillis()
    return algorithm.use {
        it.computeComponents().also { config.logStream?.println("Search elapsed: ${System.currentTimeMillis() - start}ms") }
    }
}