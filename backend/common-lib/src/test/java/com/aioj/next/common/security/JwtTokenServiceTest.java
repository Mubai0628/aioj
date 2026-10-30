package com.aioj.next.common.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** T2.2 load test: two logins in the same second must not sign the same refresh token. */
class JwtTokenServiceTest {
    @Test
    void tokensSignedInTheSameSecondDiffer() {
        JwtTokenService tokens = new JwtTokenService(new JwtProperties());

        String first = tokens.createRefreshToken(7L, "student", Set.of(Role.STUDENT));
        String second = tokens.createRefreshToken(7L, "student", Set.of(Role.STUDENT));

        assertThat(first).isNotEqualTo(second);
        assertThat(tokens.parse(first).getId()).isNotBlank().isNotEqualTo(tokens.parse(second).getId());
        assertThat(tokens.toPrincipal(tokens.parse(first)).userId()).isEqualTo(7L);
    }
}
