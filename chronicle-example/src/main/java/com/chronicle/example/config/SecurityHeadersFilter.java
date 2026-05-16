package com.chronicle.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// [SECURITY] Security headers added to ALL responses — defense in depth (Layer 8)
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        // [SECURITY] Disable legacy XSS auditor — modern recommendation; the auditor itself can cause info leaks
        response.setHeader("X-XSS-Protection", "0");
        // [SECURITY] Prevent referrer leakage to third-party origins
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // [SECURITY] Principle of least privilege — deny browser feature access
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", "default-src 'self'");
        // [SECURITY] Obscure server identity — do not advertise framework/version
        response.setHeader("Server", "chronicle");
        chain.doFilter(request, response);
    }
}
