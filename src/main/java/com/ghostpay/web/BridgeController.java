package com.ghostpay.web;

import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.service.BridgeIngestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public ingestion endpoint for real and simulated bridge nodes.
 * Bridge nodes post offline mesh packets here once they acquire internet connectivity.
 */
@RestController
@RequestMapping("/api/bridge")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class BridgeController {

    private static final Logger log = LoggerFactory.getLogger(BridgeController.class);

    private final BridgeIngestionService ingestionService;

    /**
     * Ingests an encrypted MeshPacket.
     *
     * Example:
     * POST /api/bridge/ingest
     * Headers: X-Bridge-Node-Id: phone-bridge-42, X-Hop-Count: 3
     * Body: { "packetId": "...", "ttl": 2, "createdAt": 1730000000000, "ciphertext": "..." }
     */
    @PostMapping("/ingest")
    public ResponseEntity<IngestResult> ingest(
            @Valid @RequestBody MeshPacket packet,
            @RequestHeader(value = "X-Bridge-Node-Id", defaultValue = "unknown-bridge") String bridgeNodeId,
            @RequestHeader(value = "X-Hop-Count", required = false) Integer hopCount) {

        log.info("Received ingestion request from bridge '{}' with hopCount '{}', packetId '{}'",
                bridgeNodeId, hopCount, packet.packetId());

        IngestResult result = ingestionService.ingest(packet, bridgeNodeId, hopCount != null ? hopCount : 1);
        return ResponseEntity.ok(result);
    }
}
