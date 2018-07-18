package com.github.sybila.local;

import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public interface Serializer<T> {

    void write(@NotNull DataOutputStream stream, @NotNull T item) throws IOException;

    @NotNull
    T read(@NotNull DataInputStream stream) throws IOException;

}
