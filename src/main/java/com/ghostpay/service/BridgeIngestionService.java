package com.ghostpay.service;

import com.ghostpay.crypto.HybridCryptoService;
import com.ghostpay.crypto.ServerKeyHolder;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.domain.PaymentInstruction;
import com.ghostpay.domain.Transaction;
import com.ghostpay.domain.TransactionStatus;
import com.ghostpay.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * The core 5-stage ingestion pipeline for GhostPay.
 * 
 * Pipeline stages:
 * 1. Fingerprint: Compute SHA-256(ciphertext)
 * 2. Deduplication: Atomic CAS claim via IdempotencyService
 * 3. Decrypt & Verify: RSA-OAEP + AES-256-GCM AEAD authentication
 * 4. Freshness: Validate signedAt is within 24-hour validity window
 * 5. Settlement: Execute atomic ledger write via SettlementService (with optimistic locking retry)
 */
@Service
@RequiredArgsConstructor
public class BridgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(BridgeIngestionService.class);

    private final HybridCryptoService cryptoService;
    private final ServerKeyHolder serverKeyHolder;
    private final IdempotencyService idempotencyService;
    private final SettlementService settlementService;
    private final TransactionRepository transactionRepository;

    @Value("${ghostpay.mesh.packet-freshness-hours:24}")
    private int freshnessHours;

    private static final long CLOCK_DRIFT_TOLERANCE_MILLIS = 300_000; // 5 minutes
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    /**
     * Ingests a raw mesh packet delivered by a bridge node.
     *
     * @param packet       The wire format envelope.
     * @param bridgeNodeId Identifier of the uploading bridge phone.
     * @param hopCount     Number of hops traversed in the mesh.
     * @return IngestResult with outcome and details.
     */
    public IngestResult ingest(MeshPacket packet, String bridgeNodeId, Integer hopCount) {
        if (packet == null || packet.ciphertext() == null || packet.ciphertext().isBlank()) {
            return IngestResult.invalid("unknown", "Empty or missing ciphertext");
        }

        // [Stage 1] Compute SHA-256 fingerprint of the raw ciphertext
        String packetHash = cryptoService.hashCiphertext(packet.ciphertext());

        // [Stage 2] Atomic In-Memory Claim (SETNX)
        boolean claimed = idempotencyService.claim(packetHash);
        if (!claimed) {
            log.info("Duplicate packet dropped at Stage 2 (in-memory claim collision): {}", packetHash);
            return IngestResult.duplicateDropped(packetHash);
        }

        // Secondary defense-in-depth: Check DB index in case of cache restart
        if (transactionRepository.existsByPacketHash(packetHash)) {
            log.info("Duplicate packet dropped at Stage 2 (database index collision): {}", packetHash);
            return IngestResult.duplicateDropped(packetHash);
        }

        // [Stage 3] Hybrid Decrypt and Authenticate (AES-GCM tag verification)
        PaymentInstruction instruction;
        try {
            instruction = cryptoService.decrypt(packet.ciphertext(), serverKeyHolder.getPrivateKey());
        } catch (Exception e) {
            log.warn("Cryptographic verification failed for packet {}: {}", packetHash, e.getMessage());
            return IngestResult.invalid(packetHash, "Cryptographic decryption or authentication tag verification failed");
        }

        // [Stage 4] Freshness Window Validation
        long nowMillis = Instant.now().toEpochMilli();
        long maxAgeMillis = (long) freshnessHours * 3600 * 1000;

        if (instruction.signedAt() == null) {
            return IngestResult.invalid(packetHash, "Missing signedAt timestamp");
        }

        // Check if packet is older than 24 hours
        if ((nowMillis - instruction.signedAt()) > maxAgeMillis) {
            log.warn("Packet {} rejected: expired. Age: {} ms, Limit: {} ms",
                    packetHash, (nowMillis - instruction.signedAt()), maxAgeMillis);
            return IngestResult.expired(packetHash, "Payment instruction expired: older than " + freshnessHours + " hours");
        }

        // Check for unreasonable future timestamp (clock drift allowance)
        if (instruction.signedAt() > (nowMillis + CLOCK_DRIFT_TOLERANCE_MILLIS)) {
            log.warn("Packet {} rejected: timestamp in future: {}", packetHash, instruction.signedAt());
            return IngestResult.invalid(packetHash, "Payment instruction timestamp is in the future");
        }

        // [Stage 5] Atomic Settlement with Optimistic Lock Retries
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                Transaction tx = settlementService.settle(instruction, packetHash, bridgeNodeId, hopCount);

                if (tx.getStatus() == TransactionStatus.SETTLED) {
                    return IngestResult.settled(packetHash, tx.getId());
                } else {
                    return IngestResult.failed(packetHash, tx.getFailureReason());
                }
            } catch (DataIntegrityViolationException dive) {
                // Unique constraint violation on packetHash: secondary race condition caught at DB commit
                log.warn("DB unique constraint violation on packetHash {}: {}", packetHash, dive.getMessage());
                return IngestResult.duplicateDropped(packetHash);
            } catch (ObjectOptimisticLockingFailureException oolfe) {
                log.warn("Optimistic lock collision on attempt {} for hash {}: {}", attempt, packetHash, oolfe.getMessage());
                if (attempt == MAX_OPTIMISTIC_LOCK_RETRIES) {
                    return IngestResult.failed(packetHash, "Concurrent account update collision: maximum retries exceeded");
                }
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return IngestResult.failed(packetHash, "Settlement interrupted during collision retry");
                }
            }
        }

        return IngestResult.failed(packetHash, "Settlement failed after retries");
    }
}
