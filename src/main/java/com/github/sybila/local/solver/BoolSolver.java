package com.github.sybila.local.solver;

import com.github.sybila.local.Serializer;
import com.github.sybila.local.Solver;
import org.jetbrains.annotations.NotNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class BoolSolver implements Solver<Boolean>, Serializer<Boolean> {

    @NotNull
    @Override
    public Boolean getFullSet() {
        return Boolean.TRUE;
    }

    @NotNull
    @Override
    public Boolean getEmptySet() {
        return Boolean.FALSE;
    }

    @NotNull
    @Override
    public Boolean intersect(@NotNull Boolean a, @NotNull Boolean b) {
        if (a && b) return Boolean.TRUE;
        return Boolean.FALSE;
    }

    @NotNull
    @Override
    public Boolean union(@NotNull Boolean a, @NotNull Boolean b) {
        if (a || b) return Boolean.TRUE;
        return Boolean.FALSE;
    }

    @NotNull
    @Override
    public Boolean complement(@NotNull Boolean x, @NotNull Boolean against) {
        if (against && !x) return Boolean.TRUE;
        return Boolean.FALSE;
    }

    @Override
    public boolean encloses(@NotNull Boolean x, @NotNull Boolean subset) {
        return x;
    }

    @Override
    public boolean isEmpty(@NotNull Boolean x) {
        return x;
    }

    @Override
    public double volume(@NotNull Boolean x) {
        if (x) return 1.0;
        return 0.0;
    }

    @Override
    public String print(@NotNull Boolean x) {
        return x.toString();
    }

    @Override
    public void write(@NotNull DataOutputStream stream, @NotNull Boolean x) throws IOException {
        stream.writeByte(x ? 1 : 0);
    }

    @NotNull
    @Override
    public Boolean read(@NotNull DataInputStream stream) throws IOException {
        byte b = stream.readByte();
        return b == 1;
    }

}
