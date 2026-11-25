package com.ghostpay.domain;

/**
 * Result returned by the bridge ingestion pipeline.
 */
public record IngestResult(
    String outcome,
    String packetHash,
    Long transactionId,
    String reason
) {
    public static IngestResult settled(String packetHash, Long transactionId) {
        return new IngestResult("SETTLED", packetHash, transactionId, null);
    }

    public static IngestResult duplicateDropped(String packetHash) {
        return new IngestResult("DUPLICATE_DROPPED", packetHash, null, "Packet hash already claimed");
    }

    public static IngestResult invalid(String packetHash, String reason) {
        return new IngestResult("INVALID", packetHash, null, reason);
    }

    public static IngestResult expired(String packetHash, String reason) {
        return new IngestResult("EXPIRED", packetHash, null, reason);
    }

    public static IngestResult failed(String packetHash, String reason) {
        return new IngestResult("FAILED", packetHash, null, reason);
    }
}
