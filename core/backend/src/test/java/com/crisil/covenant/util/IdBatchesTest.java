package com.crisil.covenant.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class IdBatchesTest {

    @Test
    void slicesLargeListsBelowTheParameterLimitAndKeepsEveryRow() {
        List<String> ids = IntStream.range(0, 2_225).mapToObj(i -> "CP-" + i).toList();
        List<Integer> sliceSizes = new ArrayList<>();
        List<String> rows = IdBatches.fetch(ids, slice -> {
            sliceSizes.add(slice.size());
            return slice.stream().map(id -> id + "-row").toList();
        });
        assertThat(sliceSizes).containsExactly(500, 500, 500, 500, 225);
        assertThat(rows).hasSize(2_225).startsWith("CP-0-row").endsWith("CP-2224-row");
    }

    @Test
    void acceptsSetsAndSkipsTheQueryForNoIds() {
        List<List<String>> calls = new ArrayList<>();
        assertThat(IdBatches.fetch(List.<String>of(), s -> {
            calls.add(s);
            return s;
        })).isEmpty();
        assertThat(IdBatches.<String, String>fetch(null, s -> {
            calls.add(s);
            return s;
        })).isEmpty();
        assertThat(calls).isEmpty();
        assertThat(IdBatches.fetch(new LinkedHashSet<>(List.of("a", "b")), s -> s)).containsExactly("a", "b");
    }
}
