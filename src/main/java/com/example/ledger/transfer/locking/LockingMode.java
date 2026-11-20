package com.example.ledger.transfer.locking;

/** How concurrent transfers touching the same account are kept from corrupting each other. */
public enum LockingMode {
    /** No locking at all. Exists only to demonstrate lost updates. Never use for real money. */
    NAIVE,
    /** {@code SELECT ... FOR UPDATE} both accounts (lowest id first); others wait their turn. */
    PESSIMISTIC,
    /** Read freely, detect conflicting writes with a version column, retry the loser. */
    OPTIMISTIC
}
