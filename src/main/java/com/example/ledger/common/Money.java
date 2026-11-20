package com.example.ledger.common;

/**
 * Money helpers. All amounts in this codebase are {@code long} paise (1 INR = 100 paise).
 *
 * <p>Why {@code long} and not {@code double}? Binary floating point cannot represent most
 * decimal fractions exactly (0.1 + 0.2 == 0.30000000000000004), so sums drift. Integers are
 * exact. Why not {@code BigDecimal}? It is exact too, but slower and easy to misuse
 * ({@code equals} compares scale: 1.0 != 1.00). For a single currency with a fixed
 * number of decimals, integer minor units are the industry standard.
 */
public final class Money {

    /**
     * Upper limit for a single deposit or transfer: 10 crore rupees (₹100,000,000).
     * It keeps every balance far away from {@link Long#MAX_VALUE}; balance updates also
     * use {@link Math#addExact} so an overflow would throw instead of wrapping around.
     */
    public static final long MAX_AMOUNT_PAISE = 10_000_000_000L;

    private Money() {
    }

    /** 123456 -> "₹1234.56" (for human-readable messages only, never for arithmetic). */
    public static String format(long paise) {
        String sign = paise < 0 ? "-" : "";
        long abs = Math.abs(paise);
        return sign + "₹" + (abs / 100) + "." + String.format("%02d", abs % 100);
    }
}
