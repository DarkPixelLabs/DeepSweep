package com.darkpixellabs.deepsweep.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ScanSecurityFilter extends OncePerRequestFilter {
    private static final String SCAN_PATH = "/api/scan";
    private static final String API_KEY_HEADER = "X-API-Key";

    private final String configuredApiKey;
    private final int maxRequests;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> requestTimes = new ConcurrentHashMap<>();

    public ScanSecurityFilter(
            @Value("${deepsweep.security.api-key:}") String configuredApiKey,
            @Value("${deepsweep.security.rate-limit.max-requests:30}") int maxRequests,
            @Value("${deepsweep.security.rate-limit.window-seconds:60}") long windowSeconds) {
        this(configuredApiKey, maxRequests, Duration.ofSeconds(windowSeconds), Clock.systemUTC());
    }

    ScanSecurityFilter(String configuredApiKey, int maxRequests, Duration window, Clock clock) {
        if (maxRequests < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate-limit configuration must be positive");
        }
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !SCAN_PATH.equals(request.getRequestURI()) || !"POST".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (configuredApiKey.isEmpty()) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "Scan API authentication is not configured");
            return;
        }

        String suppliedApiKey = request.getHeader(API_KEY_HEADER);
        if (!constantTimeEquals(configuredApiKey, suppliedApiKey)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key");
            return;
        }

        String client = request.getRemoteAddr();
        if (!allow(client)) {
            response.setHeader("Retry-After", Long.toString(Math.max(1, window.toSeconds())));
            writeError(response, 429, "Scan rate limit exceeded; try again later");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean allow(String client) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        Deque<Instant> timestamps = requestTimes.computeIfAbsent(client, ignored -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.removeFirst();
            }
            if (timestamps.size() >= maxRequests) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private static boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expectedBytes, suppliedBytes);
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
