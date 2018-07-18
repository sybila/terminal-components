package com.github.sybila.local

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