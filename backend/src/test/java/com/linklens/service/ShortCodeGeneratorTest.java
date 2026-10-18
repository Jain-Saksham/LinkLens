package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.config.AppProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator =
            new ShortCodeGenerator(new AppProperties("http://localhost:8080", 7, List.of("http://localhost:5173")));

    @Test
    void generatesCodesOfConfiguredLengthFromBase62Alphabet() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(generator.generate()).hasSize(7).matches("[0-9A-Za-z]+");
        }
    }

    @Test
    void codesAreEffectivelyUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(10_000);
    }
}
