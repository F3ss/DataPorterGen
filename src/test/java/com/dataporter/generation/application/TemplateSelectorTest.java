package com.dataporter.generation.application;

import com.dataporter.generation.domain.TemplateSelection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateSelectorTest {
    private final TemplateSelector selector = new TemplateSelector();

    @Test void shuffledCyclesAreBijectiveReproducibleAndChangeOrder() {
        List<Long> first = cycle(123, "items", 0, 17);
        List<Long> repeated = cycle(123, "items", 0, 17);
        List<Long> next = cycle(123, "items", 1, 17);
        List<Long> otherSeed = cycle(456, "items", 0, 17);

        assertThat(first).containsExactlyInAnyOrderElementsOf(LongStream.range(0, 17).boxed().toList());
        assertThat(repeated).containsExactlyElementsOf(first);
        assertThat(next).containsExactlyInAnyOrderElementsOf(first).isNotEqualTo(first);
        assertThat(otherSeed).isNotEqualTo(first);
    }

    @Test void supportsOneAndLargeCountsWithoutMaterializingAnOrdinalArray() {
        assertThat(selector.select(TemplateSelection.SHUFFLED_CYCLE, 1, "items", 99, 1)).isZero();
        long selected = selector.select(TemplateSelection.SHUFFLED_CYCLE, 1, "items",
                Long.MAX_VALUE - 1, Long.MAX_VALUE);
        assertThat(selected).isBetween(0L, Long.MAX_VALUE - 1);
    }

    @Test void sequentialPreservesModuloSelection() {
        assertThat(selector.select(TemplateSelection.SEQUENTIAL, 1, "items", 7, 3)).isEqualTo(1);
    }

    private List<Long> cycle(long seed, String collection, long cycle, long count) {
        return LongStream.range(0, count)
                .map(position -> selector.select(TemplateSelection.SHUFFLED_CYCLE, seed, collection,
                        cycle * count + position, count))
                .boxed().toList();
    }
}
