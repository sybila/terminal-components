package com.github.sybila

import com.github.sybila.checker.CheckerStats
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.map.SingletonStateMap
import com.github.sybila.checker.solver.SolverStats
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.OdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import java.io.File
import java.io.PrintStream
import java.util.*

typealias Params = MutableSet<Rectangle>

interface Algorithm {
    fun compute(model: OdeModel, config: Config, logStream: PrintStream?): Count<Params>
}

// ---------------------- PLUMBING TO PUT IT ALL TOGETHER ------------------------

internal fun String.readStream(): PrintStream? = when (this) {
    "null" -> null
    "stdout" -> System.out
    "stderr" -> System.err
    else -> PrintStream(File(this).apply { this.createNewFile() }.outputStream())
}

enum class ResultType { HUMAN, JSON }
enum class AlgorithmType { LOCAL, DIST }

data class Config(
        @field:Option(
                name = "-h", aliases = arrayOf("--help"), help = true,
                usage = "Print help message"
        )
        var help: Boolean = false,
        @field:Option(
                name = "-m", aliases = arrayOf("--model"), required = true,
                usage = "Path to the .bio file from which the model should be loaded."
        )
        var model: File? = null,
        @field:Option(
                name = "-ro", aliases = arrayOf("--result-output"),
                usage = "Name of stream to which the results should be printed. Filename, stdout, stderr or null."
        )
        var resultOutput: String = "stdout",
        @field:Option(
                name = "-lo", aliases = arrayOf("--log-output"),
                usage = "Name of stream to which logging info should be printed. Filename, stdout, stderr or null."
        )
        var logOutput: String = "stdout",
        @field:Option(
                name = "--fast-approximation",
                usage = "Use faster, but less precise version of linear approximation."
        )
        var fastApproximation: Boolean = false,
        @field:Option(
                name = "--cut-to-range",
                usage = "Thresholds above and below original variable range will be discarded."
        )
        var cutToRange: Boolean = false,
        @field:Option(
                name = "--disable-self-loops",
                usage = "Disable selfloop creation in transition system."
        )
        var disableSelfLoops: Boolean = false,
        @field:Option(
                name = "-r", aliases = arrayOf("--result"),
                usage = "Type of result format. Accepted values: human, json."
        )
        var resultType: ResultType = ResultType.HUMAN,
        @field:Option(
                name = "--parallelism",
                usage = "Recommended number of used threads."
        )
        var parallelism: Int = Runtime.getRuntime().availableProcessors(),
        @field:Option(
                name = "--disable-heuristic",
                usage = "Use to disable the set size state choosing heuristic"
        )
        var disableHeuristic: Boolean = false,
        @field:Option(
                name = "--algorithm-type",
                usage = "Specify the type of the algorithm."
        )
        var algorithm: AlgorithmType = AlgorithmType.LOCAL
)

fun main(args: Array<String>) {

    val config = Config()
    val parser = CmdLineParser(config)

    try {
        parser.parseArgument(*args)

        if (config.help) {
            parser.printUsage(System.err)
            System.err.println()
            return
        }

        val logStream: PrintStream? = config.logOutput.readStream()

        logStream?.println("Loading model and computing approximation...")

        val modelFile = config.model ?: throw IllegalArgumentException("Missing model file.")

        val odeModel = Parser().parse(modelFile).computeApproximation(
                fast = config.fastApproximation, cutToRange = config.cutToRange
        )

        logStream?.println("Configuration loaded. Computing transition system")

        // reset statistics

        SolverStats.reset(logStream)
        CheckerStats.reset(logStream)


        val algorithm = if (config.algorithm == AlgorithmType.LOCAL)
            LocalAlgorithm(config.parallelism, !config.disableHeuristic)
        else
            DistAlgorithm(config.parallelism)

        val counter = algorithm.compute(odeModel, config, logStream)

        logStream?.println("Component search done.")
        logStream?.println("Max number of components: ${counter.max}.")
        logStream?.println("Min number of components: ${counter.min}.")

        SolverStats.printGlobal()
        CheckerStats.printGlobal()

        config.resultOutput.readStream()?.use { outStream ->
            if (config.resultType == ResultType.HUMAN) {
                for (i in 0 until counter.size) {        // print countTSCC for each parameter set
                    outStream.println("${i + 1} terminal components: ${counter[i].map { it.asIntervals() }}")
                }
            } else {
                val result: MutableMap<String, List<StateMap<Set<Rectangle>>>> = HashMap()
                for (i in 0 until counter.size) {
                    result["${i + 1}_terminal_components"] = listOf(SingletonStateMap(0, counter[i], counter[i]))
                }

                outStream.println(printJsonRectResults(odeModel, result))
            }
        }

    } catch (e : CmdLineException) {
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
}