package com.example.ledger.transfer.locking;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * All {@link LockingStrategy} beans, looked up by {@link LockingMode}.
 *
 * <p>Spring injects every bean that implements {@code LockingStrategy} into the {@code List}
 * parameter. The default comes from {@code ledger.locking}; callers such as the demo can still
 * pick a specific one.
 */
@Component
public class LockingStrategies {

    private final Map<LockingMode, LockingStrategy> byMode = new EnumMap<>(LockingMode.class);
    private final LockingMode defaultMode;

    public LockingStrategies(List<LockingStrategy> strategies, LockingProperties properties) {
        for (LockingStrategy strategy : strategies) {
            LockingStrategy previous = byMode.put(strategy.mode(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Two strategies for " + strategy.mode());
            }
        }
        for (LockingMode mode : LockingMode.values()) {
            if (!byMode.containsKey(mode)) {
                throw new IllegalStateException("No LockingStrategy bean for " + mode);
            }
        }
        this.defaultMode = properties.locking();
    }

    public LockingStrategy get(LockingMode mode) {
        return byMode.get(mode);
    }

    public LockingMode defaultMode() {
        return defaultMode;
    }
}
