package com.aioj.next.common.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BearerTokenAuthenticationFilterTest {
    private JwtTokenService jwtTokenService;
    private BearerTokenAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        jwtTokenService = new JwtTokenService(new JwtProperties());
        filter = new BearerTokenAuthenticationFilter(jwtTokenService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accessTokenAuthenticates() throws Exception {
        String token = jwtTokenService.createAccessToken(7L, "student", List.of(Role.STUDENT));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void refreshTokenIsRejectedAsAccessToken() throws Exception {
        String token = jwtTokenService.createRefreshToken(7L, "student", List.of(Role.STUDENT));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void malformedTokenLeavesContextCleared() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer not-a-jwt");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    /** Plan S9: a student who rewrites the payload's roles keeps a signature that no longer matches it. */
    @Test
    void aTokenWhoseRolesWereEditedIsRejected() throws Exception {
        String[] parts = jwtTokenService.createAccessToken(7L, "student", List.of(Role.STUDENT)).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String edited = payload.replace("\"STUDENT\"", "\"ADMIN\"");
        assertNotEquals(payload, edited);

        assertNull(authenticate(parts[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(edited.getBytes(StandardCharsets.UTF_8)) + "." + parts[2]));
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        JwtProperties other = new JwtProperties();
        other.setHmacSecret("a-secret-that-is-not-this-services-secret");

        assertNull(authenticate(new JwtTokenService(other).createAccessToken(7L, "student", List.of(Role.ADMIN))));
    }

    @Test
    void anUnsignedTokenIsRejected() throws Exception {
        String unsigned = Jwts.builder()
                .issuer(new JwtProperties().getIssuer())
                .subject("7")
                .claim("account", "student")
                .claim("roles", List.of("ADMIN"))
                .claim("typ", JwtTokenService.TOKEN_TYPE_ACCESS)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .compact();

        assertNull(authenticate(unsigned));
    }

    private Object authenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/problems");
        request.addHeader("Authorization", "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
