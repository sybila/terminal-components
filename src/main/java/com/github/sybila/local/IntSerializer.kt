package com.github.sybila.local

import com.github.sybila.ode.safeString
import java.io.DataInputStream
import java.io.DataOutputStream

object IntSerializer : Serializer<Int> {

    override fun write(stream: DataOutputStream, item: Int) {
        stream.writeInt(item)
    }

    override fun read(stream: DataInputStream): Int {
        return stream.readInt()
    }
}

fun main(args: Array<String>) {
    val k100 = 1000

    val min = 0.0
    val max = 4.0
    val dim = 2
    val target = 360000
    val intervals = Math.pow(target.toDouble(), 1.0/dim.toDouble())
    val intervalSize = (max - min) / intervals
    print(min.safeString())
    var k = min + intervalSize
    while (k <= max) {
        print(", ${k.safeString()}")
        k += intervalSize
    }
    println(", ${max.safeString()}")
}