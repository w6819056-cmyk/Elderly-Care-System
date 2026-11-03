package com.smartelder.platform.core.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicIdsTest {

    @Test
    void generatesPrefixedSortableIdentifier() {
        String first = PublicIds.newId("ent");
        String second = PublicIds.newId("ent");

        assertThat(first).matches("ent_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(second).matches("ent_[0-9A-HJKMNP-TV-Z]{26}");
        assertThat(first).isNotEqualTo(second);
        assertThat(second.substring(4)).isGreaterThan(first.substring(4));
    }
}
