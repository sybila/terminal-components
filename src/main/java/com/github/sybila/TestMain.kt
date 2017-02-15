package com.github.sybila

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


fun main(args: Array<String>) {

    // create a File object that points to the model file
    val modelFile = File("/Users/daemontus/heap/sybila/tcbb.bio")

    // parse the .bio model and compute approximation
    val odeModel = Parser().parse(modelFile).computeApproximation(fast = true, cutToRange = true)

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
        val orResult = contradiction or customSet
        // subtraction is handled by and-not combination
        val empty = customSet and tautology.not()

        println("This should be empty: ${empty.isEmpty()}")

        // Create a state map which contains just one state with coordinates 1,1 and for this state,
        // the parametric value specified in customSet
        val stateId = coder.encodeNode(intArrayOf(1,1))
        val singleStateMap = stateId.asStateMap(customSet)

        val wholeStateSpaceOperator = TrueOperator(transitionSystem)
        val emptyOperator = FalseOperator(transitionSystem)

        // State map provides basic get and contains operations (using the state ID) but is considered immutable, so no set
        println("Value for $stateId is ${singleStateMap[stateId]} but for ${stateId+1} should be empty: ${singleStateMap[stateId+1].isEmpty()} ")

        // Operator that will compute backwards reachability from stateId.
        // Change time flow to compute forward reachability.
        val reachState = ExistsUntilOperator(
                timeFlow = true,    // true - future, false - past
                direction = DirectionFormula.Atom.True, // leave this at True
                weak = false,   // leave this at false
                pathOp = null,  // leave this null
                reach = ReferenceOperator(stateId, transitionSystem),   // Reference to a single state
                partition = transitionSystem
        )

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
    }

}