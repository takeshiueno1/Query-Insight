package com.query.insight.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PublicIdGeneratorTests {
    @Test
    void generatesUniqueUlids() {
        Set<String> values = new HashSet<>();
        for (int index = 0; index < 1_000; index++) {
            values.add(PublicIdGenerator.next());
        }
        assertThat(values).hasSize(1_000).allMatch(value -> value.matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }
}
