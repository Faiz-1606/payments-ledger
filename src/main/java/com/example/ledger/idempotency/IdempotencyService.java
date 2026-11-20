package com.example.ledger.idempotency;

import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs an operation at most once per Idempotency-Key.
 *
 * <p>Algorithm (all inside the caller's database transaction):
 * <ol>
 *   <li>{@code INSERT key ON CONFLICT DO NOTHING}.</li>
 *   <li>Inserted (1 row): we own the key. Run the operation, store its response, return it.
 *       If the operation throws, the transaction rolls back and the key row disappears with
 *       it, so the client may retry with the same key.</li>
 *   <li>Not inserted (0 rows): the key was used before. If the request hash matches, return the
 *       stored response without running anything (a replay). If not, 422.</li>
 * </ol>
 *
 * <p>Because step 1 and the money movement share one transaction, there is no window where
 * money moved but the key was not recorded, or the key was recorded but money did not move.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final JsonMapper jsonMapper;

    public IdempotencyService(IdempotencyRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * @param transferIdOf extracts the transfer id from the response, stored for traceability
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> IdempotentResult<T> execute(String key, String requestHash, Class<T> responseType,
                                           ToLongFunction<T> transferIdOf,
                                           Supplier<T> operation) {
        boolean claimed = repository.tryInsert(key, requestHash) == 1;

        if (!claimed) {
            IdempotencyRecord existing = repository.findById(key)
                    .orElseThrow(() -> new IllegalStateException("key " + key + " vanished after conflict"));
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyKeyReusedException(key);
            }
            T storedResponse = jsonMapper.readValue(existing.getResponseBody(), responseType);
            return new IdempotentResult<>(storedResponse, true);
        }

        T response = operation.get();

        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("key " + key + " missing after insert"));
        record.complete(transferIdOf.applyAsLong(response), jsonMapper.writeValueAsString(response));
        return new IdempotentResult<>(response, false);
    }

    /** The response, and whether it was replayed from storage rather than freshly produced. */
    public record IdempotentResult<T>(T response, boolean replayed) {
    }
}
