package com.ghostpay.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory idempotency cache simulating distributed atomic SETNX (e.g., Redis).
 * Protects against duplicate storms when multiple bridge nodes simultaneously
 * upload the identical mesh payment packet.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    // Maps SHA-256(ciphertext) -> timestamp of first claim
    private final ConcurrentHashMap<String, Instant> seenHashes = new ConcurrentHashMap<>();

    // Default eviction window: 24 hours (matches packet freshness window)
    private static final Duration EVICTION_WINDOW = Duration.ofHours(24);

    /**
     * Atomically claims a packet hash using CPU Compare-And-Swap (CAS).
     *
     * @param packetHash 64-character SHA-256 hex string of the ciphertext.
     * @return true if this call was the FIRST to claim the hash (proceed to decrypt & settle).
     *         false if already claimed by another bridge upload (duplicate storm drop).
     */
    public boolean claim(String packetHash) {
        if (packetHash == null || packetHash.isBlank()) {
            throw new IllegalArgumentException("packetHash cannot be null or blank");
        }

        Instant now = Instant.now();
        Instant previous = seenHashes.putIfAbsent(packetHash, now);
        boolean isFirstClaim = (previous == null);

        if (isFirstClaim) {
            log.debug("Idempotency lock acquired for packet hash: {}", packetHash);
        } else {
            log.info("Duplicate packet detected! Hash {} was already claimed at {}", packetHash, previous);
        }

        return isFirstClaim;
    }

    /**
     * Checks if a packet hash has already been seen without modifying state.
     *
     * @param packetHash SHA-256 hex string of the ciphertext.
     * @return true if already claimed, false otherwise.
     */
    public boolean isSeen(String packetHash) {
        return packetHash != null && seenHashes.containsKey(packetHash);
    }

    /**
     * Retrieves the instant when a packet hash was first claimed.
     *
     * @param packetHash SHA-256 hex string of the ciphertext.
     * @return Optional containing the claim timestamp if present.
     */
    public Optional<Instant> getClaimedTimestamp(String packetHash) {
        if (packetHash == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(seenHashes.get(packetHash));
    }

    /**
     * Releases a claimed hash.
     * Used if an operational failure occurs before settlement that requires allowing a re-attempt.
     *
     * @param packetHash SHA-256 hex string of the ciphertext.
     */
    public void release(String packetHash) {
        if (packetHash != null) {
            seenHashes.remove(packetHash);
            log.debug("Released idempotency claim for packet hash: {}", packetHash);
        }
    }

    /**
     * Clears all in-memory hashes (used when resetting the simulation).
     */
    public void reset() {
        int clearedCount = seenHashes.size();
        seenHashes.clear();
        log.info("Idempotency cache reset. Cleared {} entries.", clearedCount);
    }

    /**
     * Returns total count of unique hashes currently claimed in memory.
     */
    public int size() {
        return seenHashes.size();
    }

    /**
     * Evicts entries older than 24 hours to prevent memory leaks over time.
     * Runs every hour in the background via Spring @Scheduled.
     */
    @Scheduled(fixedRate = 3600000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(EVICTION_WINDOW);
        int initialSize = seenHashes.size();
        seenHashes.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        int evicted = initialSize - seenHashes.size();
        if (evicted > 0) {
            log.info("Idempotency eviction completed: removed {} expired hash entries.", evicted);
        }
    }
}
