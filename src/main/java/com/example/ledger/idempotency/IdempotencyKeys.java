package com.example.ledger.idempotency;

/** Constants shared by controllers that accept an Idempotency-Key. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    /** Response header telling the client the body was replayed, not freshly executed. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    /** 1-100 URL-safe characters. A UUID v4 is the recommended value. */
    public static final String PATTERN = "^[A-Za-z0-9_.:-]{1,100}$";

    private IdempotencyKeys() {
    }
}
