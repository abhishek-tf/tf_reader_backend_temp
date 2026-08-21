package com.tf.reader.hold.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HoldRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void rejectsABlankItemId() {
        assertThat(validator.validate(new HoldRequest(" "))).isNotEmpty();
        assertThat(validator.validate(new HoldRequest(""))).isNotEmpty();
    }

    @Test
    void acceptsARealItemId() {
        assertThat(validator.validate(new HoldRequest("item_1"))).isEmpty();
    }
}
