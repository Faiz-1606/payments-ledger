package com.example.ledger.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestHasherTest {

    @Test
    void sameInputGivesSameHash() {
        assertThat(RequestHasher.sha256Hex("TRANSFER|from=1|to=2|amount=5"))
                .isEqualTo(RequestHasher.sha256Hex("TRANSFER|from=1|to=2|amount=5"));
    }

    @Test
    void differentInputGivesDifferentHash() {
        assertThat(RequestHasher.sha256Hex("TRANSFER|from=1|to=2|amount=5"))
                .isNotEqualTo(RequestHasher.sha256Hex("TRANSFER|from=1|to=2|amount=50"));
    }

    @Test
    void hashIs64LowercaseHexCharacters() {
        // Known SHA-256 test vector for "abc".
        assertThat(RequestHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
