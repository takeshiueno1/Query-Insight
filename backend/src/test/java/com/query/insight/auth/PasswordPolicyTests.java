package com.query.insight.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordPolicyTests {
    @ParameterizedTest
    @ValueSource(strings = {"5050Rock", "Abcdefg1", "a1234567"})
    void acceptsEightToOneHundredTwentyEightAsciiAlphanumericCharactersContainingLettersAndDigits(String password) {
        assertThat(password.matches(PasswordPolicy.REGEX)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"505Rock", "RockOnly", "50505050", "5050#Rock", "５０５０Rock", "5050 Rock"})
    void rejectsPasswordsOutsideTheApprovedPolicy(String password) {
        assertThat(password.matches(PasswordPolicy.REGEX)).isFalse();
    }
}
