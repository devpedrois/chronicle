package com.chronicle.example.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // [SECURITY] Use getRemoteAddr() NOT X-Forwarded-For — X-Forwarded-For is spoofable
        String ip = request.getRemoteAddr();
        Bucket bucket = getBucket(ip);
        if (bucket != null && bucket.tryConsume(1)) {
            chain.doFilter(request, response);
        } else if (bucket == null) {
            // [SECURITY] Bucket map full — reject new IPs rather than allow unlimited or OOM
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\"}");
        } else {
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }
}
