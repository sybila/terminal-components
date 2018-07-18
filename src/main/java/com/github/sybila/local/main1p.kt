package com.github.sybila.local

import com.github.sybila.ResultSet
import com.github.sybila.exportResults
import com.github.sybila.local.solver.IntervalSolver
import com.github.sybila.ode.generator.rect.Rectangle
import com.github.sybila.ode.model.Parser
import com.google.gson.Gson
import java.io.DataInputStream
import java.io.File

fun main(args: Array<String>) {
    val model = Parser().parse(File("model.bio"))
    if (model.parameters.size != 1) error("Supports only one parameter.")
    val parameter = model.parameters.first()
    val rootSolver = IntervalSolver(parameter.range.first, parameter.range.second)
    println("Start loading file")
    val ts = DataInputStream(File("ts.bin").inputStream().buffered(1024 * 1024)).use {
        ExplicitTransitionSystem.read(it, rootSolver, IntSerializer, rootSolver)
    }
    println("File loaded")
    val pivot = StructureAndCardinalityPivotChooser<Int, DoubleArray>(rootSolver, ts)

    val solver = IntervalSolver(parameter.range.first, parameter.range.second)
    val start = System.currentTimeMillis()
    //println("Start algorithm")
    val alg = Algorithm(solver, ts, pivot)
    val result: ResultSet = exportResults(
            model,
            alg.execute().also {
                println("Computed in: ${System.currentTimeMillis() - start} with ${alg.iterationCount} iterations and ${solver.opCount} solver ops.")
            }.mapIndexed { i, map -> "$i attractor(s)" to listOf(map) }.toMap()
    )

    val json = Gson()
    File("result.json").writeText(json.toJson(result))
}


private fun MutableSet<Rectangle>.makeIntervalSet(): DoubleArray =
        this.map { it.asIntervals().first() }.sortedBy { it[0] }.flatMap { it.toList() }.toDoubleArray()