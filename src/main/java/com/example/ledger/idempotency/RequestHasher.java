package com.example.ledger.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Fingerprints a request so "same key, same request?" can be answered later.
 *
 * <p>We hash a <em>canonical string</em> built from the validated fields, e.g.
 * {@code TRANSFER|from=1|to=2|amount=500}, not the raw JSON bytes. Raw JSON differs for
 * requests that mean the same thing ({@code {"a":1,"b":2}} vs {@code {"b":2, "a":1}}), which
 * would wrongly produce a 422 for an honest retry.
 */
public final class RequestHasher {

    private RequestHasher() {
    }

    public static String sha256Hex(String canonicalRequest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in the JDK", e);
        }
    }
}
