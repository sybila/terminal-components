package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.CheckerStats
import com.github.sybila.checker.Operator
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.map.RangeStateMap
import com.github.sybila.checker.map.SingletonStateMap
import com.github.sybila.checker.operator.*
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.checker.solver.SolverStats
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import org.kohsuke.args4j.CmdLineException
import org.kohsuke.args4j.CmdLineParser
import org.kohsuke.args4j.Option
import java.io.File
import java.io.PrintStream
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

// --------------------- GENERAL HELPER FUNCTIONS --------------------------

// General class hierarchy:
// States are represented by integer ID.
// Parameters are represented using the generic T/Params type.
// StateMap is a standard State -> Param mapping, but can have various (symbolic/explicit) implementations
// Operator is something like a lazy state map - it is a class that can compute results (based on other operators)
// and then return them (from the compute method).
// Channel is a know-it-all context which unites Model/Partition/Solver/Communicator into one context.

// shorthand for creating an And/Or/Not operators
fun <T: Any> Channel<T>.And(left: Operator<T>, right: Operator<T>) = AndOperator(left, right, this)
fun <T: Any> Channel<T>.Or(left: Operator<T>, right: Operator<T>) = OrOperator(left, right, this)
fun <T: Any> Channel<T>.Not(left: Operator<T>) = ComplementOperator(TrueOperator(this), left, this)

// forward reachability
fun <T: Any> Channel<T>.FWD(inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = false,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

// backward reachability
fun <T: Any> Channel<T>.BWD (inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = true,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

// operator which does not compute anything, only returns a fixed state map
// (f.e. you can use this to provide an operator which returns just one state)
class ExplicitOperator<out Params : Any>(
        private val data: StateMap<Params>
) : Operator<Params> {

    override fun compute(): StateMap<Params> = data

}

// check if results computed by given operator is empty
fun <T: Any> Channel<T>.isResultEmpty(op: Operator<T>) = op.compute().entries().asSequence().all { it.second.isNotSat() }

// ----------------------- MAIN ALGORITHM -------------------------------------

class Algorithm(
        parallelism: Int,
        private val useHeuristics: Boolean
) {

    val executor = Executors.newFixedThreadPool(parallelism)!!
    //private val executor = Executors.newFixedThreadPool(2)
    private val pending = ArrayList<Future<*>>()

    // This is the generic version of the terminal ssc algorithm:
    fun <T: Any> Channel<T>.paramRecursionTSCC(op: Operator<T>, paramCounts: Count<T>) {
        var universe = op
        while (!isResultEmpty(universe)) {
            val (v, vParams) = if (useHeuristics) choose(universe) else chooseSimple(universe)

            // limit that restricts the whole state space to parameters associated with v
            val limit = ExplicitOperator(RangeStateMap(0 until stateCount, value = vParams, default = ff))

            // operator for single state v
            val vOp = ExplicitOperator(v.asStateMap(vParams))

            // F - states reachable from v
            val F = And(universe, FWD(vOp))
            // B - states that can reach v but are also reachable from v
            val B = And(F, BWD(vOp))
            // F\B - states reachable from v but not reaching v - i.e. a potentially new terminal component
            // will be empty if v is on a terminal cycle
            val F_minus_B = And(F, Not(B))

            if (!isResultEmpty(F_minus_B)) {
                startAction(F_minus_B, paramCounts)
            }

            // BB - B' - All valid predecessors of F
            val BB = And(universe, BWD(F))

            // V \ BB restricted to the parameters of state v
            val V_minus_BB = And(And(universe, limit), Not(BB))

            // compute parameter union across the potential new component
            val componentParams = V_minus_BB.compute().entries().asSequence().fold(ff) { acc, (_, params) ->
                acc or params
            }

            if (componentParams.isSat()) {
                paramCounts.push(componentParams)
                startAction(V_minus_BB, paramCounts)
            }

            // cut the processed area out of the universe and repeat
            universe = And(universe, Not(limit))
        }
    }

    private var lastPrint = AtomicLong(System.currentTimeMillis())

    fun printActionProgress() {
        val last = lastPrint.get()
        if (System.currentTimeMillis() > last + 3000 && lastPrint.compareAndSet(last, System.currentTimeMillis())) {
            synchronized(pending) {
                println("Action queue: ${pending.size}")
            }
        }
    }

    fun <T: Any> Channel<T>.startAction(states: Operator<T>, paramCounts: Count<T>) {
        // start new task in an executor and save it into pending
        synchronized(pending) {
            val future = executor.submit {
                paramRecursionTSCC(states, paramCounts)
            }
            pending.add(future)
            printActionProgress()
        }
    }

    fun blockWhilePending() {
        // go through the pending tasks one by one, removing the ones that are
        // completed and terminating when all tasks are done
        do {
            val first = synchronized(pending) {
                pending.firstOrNull()
            }
            first?.let {
                it.get()
                synchronized(pending) {
                    pending.remove(it)
                }
                printActionProgress()
            }
        } while (first != null)
    }

    // find first non-empty state in operator results and return is lifted to an operator
    fun <T: Any> choose(op: Operator<T>): Pair<Int, T> {
        var max: Pair<Int, T>? = null
        var maxCovered: Double = 0.0
        op.compute().entries().forEach { (state, p) ->
            val params = p as MutableSet<Rectangle>
            val covered = params.fold(0.0) { acc, rect ->
                acc + rect.asIntervals().map { Math.abs(it[1] - it[0]) }.fold(1.0) { acc, dim -> acc * dim }
            }
            if (covered > maxCovered) {
                max = state to p
                maxCovered = covered
            }
        }
        return max!!
    }

    fun <T: Any> chooseSimple(op: Operator<T>): Pair<Int, T> = op.compute().entries().next()

}
// ---------------------- PLUMBING TO PUT IT ALL TOGETHER ------------------------

internal fun String.readStream(): PrintStream? = when (this) {
    "null" -> null
    "stdout" -> System.out
    "stderr" -> System.err
    else -> PrintStream(File(this).apply { this.createNewFile() }.outputStream())
}

enum class ResultType { HUMAN, JSON }

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
        var disableHeuristic: Boolean = false
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

        val transitionSystem = SingletonChannel(RectangleOdeModel(odeModel,
                createSelfLoops = !config.disableSelfLoops
        ).asSingletonPartition())

        // reset statistics

        SolverStats.reset(logStream)
        CheckerStats.reset(logStream)

        // enter transition system context
        Algorithm(config.parallelism, !config.disableHeuristic).run {
            transitionSystem.run {

                // transition system is not synchronized, however, it is read-safe.
                // hence if we pre-compute all transitions before actually starting the algorithm,
                // we can safely perform operations in parallel.
                (0 until stateCount).forEach {
                    it.predecessors(true)
                    it.predecessors(false)
                    it.successors(true)
                    it.successors(false)
                }

                logStream?.println("Transition system computed. Starting component search...")

                // counter is synchronized
                val counter = Count(this)

                // compute results
                val allStates = TrueOperator(this)
                startAction(allStates, counter)

                blockWhilePending()
                executor.shutdown()

                logStream?.println("Component search done.")
                logStream?.println("Max number of components: ${counter.max}.")
                logStream?.println("Min number of components: ${counter.min}.")

                config.resultOutput.readStream()?.use { outStream ->
                    if (config.resultType == ResultType.HUMAN) {
                        for (i in 0 until counter.size) {        // print countTSCC for each parameter set
                            outStream.println("${i + 1} terminal components: ${counter[i].map { it.asIntervals() }}")
                        }
                    } else {
                        val result: MutableMap<String, List<StateMap<Set<Rectangle>>>> = HashMap()
                        for (i in 0 until counter.size) {
                            result["${i + 1}_terminal_components"] = listOf(SingletonStateMap(0, counter[i], ff))
                        }

                        outStream.println(printJsonRectResults(odeModel, result))
                    }
                }
            }
        }

    } catch (e : CmdLineException) {
        // if there's a problem in the command line,
        // you'll get this exception. this will report
        // an error message.
        System.err.println(e.message)
        System.err.println("pithya [options...]")
        // print the list of available options (with fresh defaults)
        CmdLineParser(Config()).printUsage(System.err)
        System.err.println()

        return
    }
}