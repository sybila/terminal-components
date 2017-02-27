package com.github.sybila

import com.github.sybila.checker.Channel
import com.github.sybila.checker.Operator
import com.github.sybila.checker.Partition
import com.github.sybila.checker.StateMap
import com.github.sybila.checker.channel.SingletonChannel
import com.github.sybila.checker.operator.*
import com.github.sybila.checker.partition.asSingletonPartition
import com.github.sybila.huctl.DirectionFormula
import com.github.sybila.ode.generator.NodeEncoder
import com.github.sybila.ode.generator.rect.RectangleOdeModel
import com.github.sybila.ode.generator.rect.rectangleOf
import com.github.sybila.ode.model.Parser
import com.github.sybila.ode.model.computeApproximation
import java.io.File

fun <T: Any> Channel<T>.And(left: Operator<T>, right: Operator<T>) = AndOperator(left, right, this)
fun <T: Any> Channel<T>.Not(left: Operator<T>) = ComplementOperator(TrueOperator(this), left, this)

fun <T: Any> Channel<T>.FWD(inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = false,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

fun <T: Any> Channel<T>.BWD (inner: Operator<T>) = ExistsUntilOperator(
        timeFlow = true,
        direction = DirectionFormula.Atom.True,
        weak = false, pathOp = null,
        reach = inner, partition = this
)

class SingleStateOperator<out Params : Any>(
        state: Int,
        value: Params,
        partition: Partition<Params>
) : Operator<Params> {
    private val value = partition.run {
        if (state in this) state.asStateMap(value) else emptyStateMap()
    }
    override fun compute(): StateMap<Params> = value
}

fun <T: Any> Channel<T>.isResultEmpty(op: Operator<T>) = op.compute().entries().asSequence().all { it.second.isNotSat() }

fun <T: Any> Channel<T>.choose(op: Operator<T>): Operator<T> = op.compute().entries().asSequence()
        .first { it.second.isSat() }
        .let { entry -> SingleStateOperator(entry.first, entry.second, this) }

var count = 1


fun main(args: Array<String>) {

    // create a File object that points to the model file
    //val modelFile = File("E:\\Model checking Group\\pithya\\biodivineGUI\\example\\tcbb_model/model_indep.bio")
    //val modelFile = File("E:\\Model checking Group\\terminal-components\\for_benchmark\\enumerative\\repressilators - affine\\4D\\model_4D_1P_10kR.bio")
    val modelFile = File("E:\\Model checking Group\\terminal-components\\for_benchmark\\enumerative\\repressilators - affine\\3D\\model_3D_1P_8kR.bio")

    // parse the .bio model and compute approximation
    val odeModel = Parser().parse(modelFile).computeApproximation(fast = true, cutToRange = false)

    // Create the transition system as required by the EU operator
    // .asSingletonPartition will take original model and turn it into a system "partitioned" into one partition
    // SingletonChannel will then wrap this "partitioned" system into a communicator that is doing nothing (since
    // there is only one partition, so no communication should occur)
    val transitionSystem = SingletonChannel(RectangleOdeModel(odeModel, createSelfLoops = true).asSingletonPartition())

    // coder is an object that can encode/decode state coordinates to unique state ID in range of [0, stateCount)
    // it also provides a ton of utility functions and properties
    // stateCount - number of states in the model
    // vertexCount - number of vertices in the model (each state has 2^D vertices, but they can be shared between states)
    // encodeNode(intArray) - encode state coordinates into single ID
    // decodeNode(int) - decode state ID into coordinates array
    // higher/lowerNode(stateID, dimensionIndex) - compute ID of the state which is one step higher/lower above/below given state
    //                                             (if no such state exists in the model, return null)
    val coder = NodeEncoder(odeModel)

    //Note that transitionSystem is also a rectangular solver. In order to use it, we have to switch to it's "context" like this:
    transitionSystem.run {

        val c = Count(transitionSystem)
        println("One component: C1{${c[0]}}")
        c.push(mutableSetOf(rectangleOf(0.1,0.2)))
        println("Two components: C1{${c[0]}} C2{${c[1]}}")
        c.push(mutableSetOf(rectangleOf(0.15,0.25)))
        println("Three components: C1{${c[0]}} C2{${c[1]}} C3{${c[2]}}")
        println("Max. number of components: ${c.size}")

        // here we can use everything defined in the Solver interface
        val tautology = tt  //constant for full parameter set
        val contradiction = ff //constant for empty parameter set

        // parameter set which contains two rectangles
        // since this model has only one parameter, the rectangles are just intervals
        val customSet = mutableSetOf(
                rectangleOf(0.0, 1.0),
                rectangleOf(1.5, 2.0)
        )

        // everything should be easy to log:
        println("Tautology: $tautology Contradiction: $contradiction Custom: $customSet")

        // basic operations
        val andResult = tautology and customSet
        //println("andResult is ${andResult}")
        val orResult = contradiction or customSet
        // subtraction is handled by and-not combination
        val empty = customSet and tautology.not()

        println("This should be empty: ${empty.isEmpty()}")

        // Create a state map which contains just one state with coordinates 1,1 and for this state,
        // the parametric value specified in customSet
        val stateId = coder.encodeNode(intArrayOf(1, 1))
        val singleStateMap = stateId.asStateMap(customSet)

        val wholeStateSpaceOperator = TrueOperator(transitionSystem)
        val emptyOperator = FalseOperator(transitionSystem)

        // State map provides basic get and contains operations (using the state ID) but is considered immutable, so no set
        println("Value for $stateId is ${singleStateMap[stateId]} but for ${stateId + 1} should be empty: ${singleStateMap[stateId + 1].isEmpty()} ")

        // Operator that will compute backwards reachability from stateId.
        // Change time flow to compute forward reachability.
        // fwd(V,S)== AndOperator(V, ExistsUntilOperator(false,DirectionFormula.Atom.True,false,null,S,transitionSystem), transitionSystem)
        // bwd(V,S)== AndOperator(V, ExistsUntilOperator(true,DirectionFormula.Atom.True,false,null,S,transitionSystem), transitionSystem)
        val reachState = ExistsUntilOperator(
                timeFlow = true,    // true - future, false - past
                direction = DirectionFormula.Atom.True, // leave this at True
                weak = false,   // leave this at false
                pathOp = null,  // leave this null
                reach = ReferenceOperator(stateId, transitionSystem),   // Reference to a single state
                partition = transitionSystem
        )

        /*
        val V = wholeStateSpaceOperator

        val v = ReferenceOperator(stateId, transitionSystem)
        val F = FWD(v)
        val B = And(F, BWD(v))

        val F_minus_B = And(F, Not(B))
        val isEmpty = isResultEmpty(F_minus_B)
         */
        //val V_minus_BB = And(wholeStateSpaceOperator, Not(F))

        recursionTSCC(wholeStateSpaceOperator)

        // This will give you a valid state map with the results
        val reachabilityResults = reachState.compute()

        println("Result size: ${reachabilityResults.entries().asSequence().count()}")

        val reachBack = ExistsUntilOperator(
                timeFlow = false,
                direction = DirectionFormula.Atom.True,
                weak = false,
                pathOp = null,
                reach = reachState,
                partition = transitionSystem
        )

        println("Second result size: ${reachBack.compute().entries().asSequence().count()}")

        // This is a logical operator which works for the whole state maps
        // There is an AndOperator, OrOperator and ComplementOperator
        // (use complement against the whole state space to implement negation)
        val andOperator = AndOperator(reachState, reachBack, transitionSystem)

        println("Conjunction size: ${andOperator.compute().entries().asSequence().count()}")
        println("Number of terminal components is: ${count}")

        // TODO: Matko vymaz si tento krasny debbug
        //println(wholeStateSpaceOperator.compute().prettyPrint())
        //println(wholeStateSpaceOperator.compute().entries().asSequence().count())
        //val paramcounts = mutableListOf(tt)
        //paramcounts[0]=9
        /*
        println(reachBack.compute().prettyPrint())
        for (item in reachBack.compute().entries()) print(item)
        println()
        for (item in reachBack.compute().entries().asSequence()) print(item)
        println()
        for (item in reachBack.compute().entries()) print(item)
        println()
        var a = contradiction
        println(a)
        for (item in reachBack.compute().entries().asSequence()) a = a or (item.second)
        println(a)
        a = mutableSetOf(rectangleOf(1.5, 2.0)) or mutableSetOf(rectangleOf(3.0, 4.0))
        println(a)
        //println("end")

        transitionSystem.tt
        */
        println("Number of terminal components for particular parameter space:")
        paramRecursionTSCC(wholeStateSpaceOperator,Count(transitionSystem))

        // TODO: vytvorit paralelnu verziu
        // 1, pocitanie worker 1 a worker 2 nezavislo
        // 2, rozsekat state space a skrz to pocitat fwd a bwd, sync vysledok
        // 3, verzia kde zistime prinajhorsom dolny odhad

    }

}

fun <T: Any> Channel<T>.recursionTSCC(op: Operator<T>) {
    val v= choose(op)                                               // v is now first state or error
    //println(v.compute().prettyPrint())
    val F= FWD(v)                                                   // compute fwd(V,v)
    //println("F: ${F.compute().prettyPrint()}")
    val B= And(F, BWD(v))                                           // compute bwd(F,v) - states reachable from v  and reaching v
    val F_minus_B = And(F, Not(B))                                  // F\B - states reachable from v but not reaching v
    //println("F_minus_B: ${F_minus_B.compute().prettyPrint()}")
    //println(isResultEmpty(F_minus_B))
    if (!isResultEmpty(F_minus_B))
        recursionTSCC(F_minus_B)
    val BB=BWD(F)                                                   // compute bwd(V,F)
    val V_minus_BB = And(op, Not(BB))                               // V\BB - states not reaching v
    //println("V_minus_BB: ${V_minus_BB.compute().prettyPrint()}")
    //println(isResultEmpty(V_minus_BB))
    if (!isResultEmpty(V_minus_BB))
        {
            //println("tu som")
            //println("count= ${count}")
            count +=1
            //println("count= ${count}")
            recursionTSCC(V_minus_BB)
        }
}

fun <T: Any> Channel<T>.paramRecursionTSCC(op: Operator<T>, paramcounts: Count<T>) {
    val v= choose(op)                                                                        // v is now first state or error!!!!
    val F= FWD(v)                                                                            // compute fwd(V,v)
    val B= And(F, BWD(v))                                                                    // compute bwd(F,v) - states reachable from v  and reaching v
    val F_minus_B = And(F, Not(B))                                                           // F\B - states reachable from v but not reaching v
    if (!isResultEmpty(F_minus_B))
        paramRecursionTSCC(F_minus_B,paramcounts)
    val BB=BWD(F)                                                                           // compute bwd(V,F)
    val V_minus_BB = And(op, Not(BB))                                                       // V\BB - states not reaching v
    if (!isResultEmpty(V_minus_BB))
    {
        var a = ff
        for (item in V_minus_BB.compute().entries().asSequence()) a = a or (item.second)    // unite all parametrisation through V_minus_BB
        paramcounts.push(a)                                                                 // distribute this parametrisation set to count
        for (i in 0 until paramcounts.size) {                                               // print countTSCC for each parameter set
            print("${i+1} terminal components:" + paramcounts[i].toString()+"; ")
        }
        println()
        paramRecursionTSCC(V_minus_BB,paramcounts)
    }
}
