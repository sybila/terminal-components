package com.github.sybila

import org.kohsuke.args4j.Option
import java.io.Closeable
import java.io.File
import java.io.PrintStream

data class Config(
        @field:Option(
                name = "-h", aliases = ["--help"], help = true,
                usage = "Print help message"
        )
        var help: Boolean = false,
        @field:Option(
                name = "-m", aliases = ["--model"], required = true,
                usage = "Path to the .bio file from which the model should be loaded."
        )
        var model: File? = null,
        @field:Option(
                name = "-ro", aliases = ["--result-output"],
                usage = "Name of stream to which the results should be printed. Filename, stdout, stderr or null."
        )
        var resultOutput: String = "stdout",
        @field:Option(
                name = "-lo", aliases = ["--log-output"],
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
                name = "-r", aliases = ["--result"],
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
): Closeable {

    enum class ResultType { HUMAN, JSON }
    enum class AlgorithmType { LOCAL, DIST }

    // we have to be lazy, because at the start, config is not initialized!
    val resultStream: PrintStream? by lazy { resultOutput.readStream() }

    val logStream: PrintStream? by lazy { logOutput.readStream() }

    private fun String.readStream(): PrintStream? = when (this) {
        "null" -> null
        "stdout" -> System.out
        "stderr" -> System.err
        else -> PrintStream(File(this).apply { this.createNewFile() }.outputStream())
    }

    override fun close() {
        resultStream?.close()
        logStream?.close()
    }

}