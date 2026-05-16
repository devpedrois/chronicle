package com.chronicle.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Shared SHA-256 utility.
 *
 * <p>Formula used throughout the system: {@code sha256(aggregateId + "|" + version + "|" + state)}.
 * Binding all three fields prevents state, version, and cross-aggregate injection attacks.
 */
public final class ChecksumUtil {

    // MessageDigest is not thread-safe; ThreadLocal avoids both lock contention and per-call getInstance().
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    private ChecksumUtil() {}

    /**
     * Computes SHA-256 hex digest of the given input string (UTF-8 encoded).
     */
    public static String sha256(String input) {
        MessageDigest digest = DIGEST.get();
        digest.reset();
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }
}
