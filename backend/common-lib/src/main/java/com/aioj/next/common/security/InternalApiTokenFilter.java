package com.aioj.next.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class InternalApiTokenFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(InternalApiTokenFilter.class);
    private static final String INTERNAL_ROOT = "/api/v1/internal";
    private static final String HEADER = "X-Internal-Token";
    private final List<CallerToken> tokens = new ArrayList<>();
    private final Map<String, Set<String>> access;

    public InternalApiTokenFilter(Map<String, InternalServiceProperties.Credential> inbound,
                                  Map<String, Set<String>> access) {
        this.access = Map.copyOf(access);
        inbound.forEach((caller, credential) -> {
            if (credential == null) { return; }
            add(caller, credential.getToken());
            add(caller, credential.getNextToken());
            if (credential.getToken() == null || credential.getToken().isBlank()) {
                log.warn("Internal API current credential missing caller={}; requests fail closed without a configured token", caller);
            }
        });
        if (tokens.isEmpty()) {
            log.warn("Internal API has no credentials; all internal requests fail closed");
        }
    }

    private void add(String caller, String token) {
        if (token != null && !token.isBlank()) { tokens.add(new CallerToken(caller, digest(token))); }
    }

    /** Hash both inputs to fixed-length bytes before constant-time comparison; no secret is logged. */
    public static boolean matches(String token, String current, String next) {
        byte[] supplied = digest(token == null ? "" : token);
        boolean currentMatch = current != null && !current.isBlank()
                && MessageDigest.isEqual(digest(current), supplied);
        boolean nextMatch = next != null && !next.isBlank()
                && MessageDigest.isEqual(digest(next), supplied);
        return token != null && (currentMatch | nextMatch);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!segmentPrefix(path, INTERNAL_ROOT)) {
            filterChain.doFilter(request, response);
            return;
        }
        List<String> headers = Collections.list(request.getHeaders(HEADER));
        String token = headers.size() == 1 ? headers.get(0) : null;
        byte[] supplied = digest(token == null || token.length() > 4096 ? "" : token);
        String caller = null;
        boolean ambiguous = false;
        for (CallerToken expected : tokens) {
            boolean match = MessageDigest.isEqual(expected.digest(), supplied);
            if (match) {
                ambiguous |= caller != null && !caller.equals(expected.caller());
                caller = expected.caller();
            }
        }
        if (token == null || token.isBlank() || token.length() > 4096 || caller == null || ambiguous) {
            deny(response, 401, "unknown", path, "missing_or_invalid_credential");
            return;
        }
        String matchedCaller = caller;
        boolean allowed = access.entrySet().stream().anyMatch(entry ->
                segmentPrefix(path, entry.getKey()) && entry.getValue().contains(matchedCaller));
        if (!allowed) {
            deny(response, 403, caller, path, "prefix_not_authorized");
            return;
        }
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(caller, null, List.of(
                new SimpleGrantedAuthority("ROLE_INTERNAL"),
                new SimpleGrantedAuthority("INTERNAL_CALLER_" + caller))));
        SecurityContextHolder.setContext(context);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private static boolean segmentPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static void deny(HttpServletResponse response, int status, String caller, String path,
                             String reason) throws IOException {
        String safePath = path.replaceAll("[\\r\\n\\t]", "_");
        log.warn("Internal API denied caller={} path={} reason={}", caller,
                safePath.substring(0, Math.min(256, safePath.length())), reason);
        response.sendError(status, status == 401 ? "internal authentication required" : "internal access denied");
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record CallerToken(String caller, byte[] digest) { }
}
