package com.ghostpay.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyServiceTest {

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService();
    }

    @Test
    @DisplayName("First claim of a unique hash must succeed and return true")
    void shouldClaimUniqueHash() {
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        boolean claimed = idempotencyService.claim(hash);

        assertTrue(claimed, "First claim must return true");
        assertTrue(idempotencyService.isSeen(hash), "Hash must be marked as seen");
        assertEquals(1, idempotencyService.size());
    }

    @Test
    @DisplayName("Second claim of the same hash must return false (duplicate detected)")
    void shouldRejectDuplicateClaim() {
        String hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

        assertTrue(idempotencyService.claim(hash), "Initial claim should succeed");
        assertFalse(idempotencyService.claim(hash), "Subsequent claim must be rejected as duplicate");
        assertEquals(1, idempotencyService.size(), "Size must remain 1");
    }

    @Test
    @DisplayName("Releasing a claim allows the hash to be claimed again")
    void shouldAllowReClaimAfterRelease() {
        String hash = "sample-packet-hash-12345";

        assertTrue(idempotencyService.claim(hash));
        idempotencyService.release(hash);

        assertFalse(idempotencyService.isSeen(hash));
        assertTrue(idempotencyService.claim(hash), "Should succeed claiming again after release");
    }

    @Test
    @DisplayName("Reset clears all claimed hashes")
    void shouldClearOnReset() {
        idempotencyService.claim("hash-1");
        idempotencyService.claim("hash-2");
        assertEquals(2, idempotencyService.size());

        idempotencyService.reset();
        assertEquals(0, idempotencyService.size());
        assertFalse(idempotencyService.isSeen("hash-1"));
        assertFalse(idempotencyService.isSeen("hash-2"));
    }

    @Test
    @DisplayName("Should retrieve claim timestamp when present")
    void shouldReturnClaimTimestamp() {
        String hash = "timestamp-test-hash";
        idempotencyService.claim(hash);

        Optional<Instant> timestamp = idempotencyService.getClaimedTimestamp(hash);
        assertTrue(timestamp.isPresent());
        assertTrue(timestamp.get().isBefore(Instant.now().plusSeconds(1)));
    }

    @Test
    @DisplayName("Evict expired should remove entries older than 24 hours")
    void shouldEvictExpiredEntries() {
        @SuppressWarnings("unchecked")
        ConcurrentHashMap<String, Instant> map =
                (ConcurrentHashMap<String, Instant>) ReflectionTestUtils.getField(idempotencyService, "seenHashes");

        assertNotNull(map);
        // Put an entry from 25 hours ago
        map.put("stale-hash", Instant.now().minus(Duration.ofHours(25)));
        // Put a fresh entry from 1 hour ago
        map.put("fresh-hash", Instant.now().minus(Duration.ofHours(1)));

        assertEquals(2, idempotencyService.size());

        idempotencyService.evictExpired();

        assertEquals(1, idempotencyService.size());
        assertFalse(idempotencyService.isSeen("stale-hash"), "Stale hash must be evicted");
        assertTrue(idempotencyService.isSeen("fresh-hash"), "Fresh hash must remain");
    }

    @Test
    @DisplayName("Null or blank hash must throw IllegalArgumentException")
    void shouldThrowOnInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> idempotencyService.claim(null));
        assertThrows(IllegalArgumentException.class, () -> idempotencyService.claim(""));
        assertThrows(IllegalArgumentException.class, () -> idempotencyService.claim("   "));
    }
}
