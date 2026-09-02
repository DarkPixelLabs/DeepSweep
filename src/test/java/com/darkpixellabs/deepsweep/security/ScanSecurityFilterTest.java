package com.darkpixellabs.deepsweep.security;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanSecurityFilterTest {
    private static final Instant START = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void rejectsMissingApiKey() throws ServletException, IOException {
        var filter = filter(2, 60);
        var request = request("127.0.0.1");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Invalid or missing API key"));
    }

    @Test
    void acceptsValidApiKey() throws ServletException, IOException {
        var filter = filter(2, 60);
        var request = request("127.0.0.1");
        request.addHeader("X-API-Key", "test-key");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals(request, chain.getRequest());
    }

    @Test
    void rateLimitsPerClient() throws ServletException, IOException {
        var filter = filter(2, 60);
        for (int i = 0; i < 2; i++) {
            var request = request("127.0.0.1");
            request.addHeader("X-API-Key", "test-key");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        var blocked = request("127.0.0.1");
        blocked.addHeader("X-API-Key", "test-key");
        var response = new MockHttpServletResponse();
        filter.doFilter(blocked, response, new MockFilterChain());

        assertEquals(429, response.getStatus());
        assertEquals("60", response.getHeader("Retry-After"));
    }

    @Test
    void rateLimitResetsAfterWindow() throws ServletException, IOException {
        var clock = new MutableClock(START);
        var filter = new ScanSecurityFilter("test-key", 1, Duration.ofSeconds(60), clock);
        var first = request("127.0.0.1");
        first.addHeader("X-API-Key", "test-key");
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());

        clock.advance(Duration.ofSeconds(61));
        var second = request("127.0.0.1");
        second.addHeader("X-API-Key", "test-key");
        var response = new MockHttpServletResponse();
        filter.doFilter(second, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    @Test
    void missingServerKeyFailsClosed() throws ServletException, IOException {
        var filter = new ScanSecurityFilter("", 2, Duration.ofSeconds(60), Clock.fixed(START, ZoneOffset.UTC));
        var request = request("127.0.0.1");
        request.addHeader("X-API-Key", "test-key");
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
    }

    private static ScanSecurityFilter filter(int maxRequests, long seconds) {
        return new ScanSecurityFilter("test-key", maxRequests, Duration.ofSeconds(seconds),
                Clock.fixed(START, ZoneOffset.UTC));
    }

    private static MockHttpServletRequest request(String remoteAddress) {
        var request = new MockHttpServletRequest("POST", "/api/scan");
        request.setRemoteAddr(remoteAddress);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
