package com.chronicle.example.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// [SECURITY] Rate limiting — 100 req/min per IP, Layer 8
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    // [SECURITY] Bound the bucket map to prevent heap exhaustion via many distinct IPs (botnet attack)
    private static final int MAX_BUCKETS = 100_000;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Bucket getBucket(String ip) {
        Bucket existing = buckets.get(ip);
        if (existing != null) {
            return existing;
        }
        // [SECURITY] Deny new IPs when map is full — prevents OOM via IP exhaustion
        if (buckets.size() >= MAX_BUCKETS) {
            return null;
        }
        return buckets.computeIfAbsent(ip, k -> Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(100)
                        .refillGreedy(100, Duration.ofMinutes(1))
                        .build())
                .build());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        // [SECURITY] Use getRemoteAddr() NOT X-Forwarded-For — X-Forwarded-For is spoofable
        String ip = request.getRemoteAddr();
        Bucket bucket = getBucket(ip);
        if (bucket != null && bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else {
            // [SECURITY] Rate limit exceeded or bucket map full — 429 with retry guidance
            // Retry-After header required by RFC 6585 §4 — HTTP middleware and API gateways
            // use the header for automatic backoff; body alone is insufficient for machine clients
            response.setStatus(429);
            response.setHeader("Retry-After", "60");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Rate limit exceeded\",\"retry_after_seconds\":60}");
        }
    }
}
