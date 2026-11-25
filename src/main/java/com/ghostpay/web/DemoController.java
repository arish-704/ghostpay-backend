package com.ghostpay.web;

import com.ghostpay.crypto.ServerKeyHolder;
import com.ghostpay.domain.Account;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.domain.SendPaymentRequest;
import com.ghostpay.domain.Transaction;
import com.ghostpay.repository.TransactionRepository;
import com.ghostpay.service.DemoService;
import com.ghostpay.simulator.MeshSimulatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller to drive the interactive demo and local simulator:
 * 1. Inspect live accounts and transactions.
 * 2. Simulate sender phone packet injection.
 * 3. Step through gossip propagation rounds.
 * 4. Trigger bridge uploads.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final ServerKeyHolder serverKeyHolder;
    private final DemoService demoService;
    private final MeshSimulatorService simulatorService;
    private final TransactionRepository transactionRepository;

    @Value("${ghostpay.mesh.packet-ttl-max:10}")
    private int maxTtl;

    @GetMapping("/server-key")
    public ResponseEntity<Map<String, String>> getServerKey() {
        return ResponseEntity.ok(Map.of(
                "algorithm", "RSA-2048",
                "transformation", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                "publicKeyBase64", serverKeyHolder.getPublicKeyBase64()
        ));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<Account>> getAccounts() {
        return ResponseEntity.ok(demoService.getAllAccounts());
    }

    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getRecentTransactions() {
        return ResponseEntity.ok(transactionRepository.findTop20ByOrderBySettledAtDesc());
    }

    @GetMapping("/mesh/state")
    public ResponseEntity<List<Map<String, Object>>> getMeshState() {
        return ResponseEntity.ok(simulatorService.getNetworkState());
    }

    /**
     * Simulates an offline sender phone (Alice) composing, hybrid-encrypting,
     * and injecting a payment packet into the mesh.
     */
    @PostMapping("/demo/send")
    public ResponseEntity<MeshPacket> sendPayment(@Valid @RequestBody SendPaymentRequest request) {
        log.info("Simulating sender creation: from {} to {} amount ₹{}",
                request.senderVpa(), request.receiverVpa(), request.amount());

        int initialTtl = Math.min(5, maxTtl);
        MeshPacket packet = demoService.createPaymentPacket(
                request.senderVpa(),
                request.receiverVpa(),
                request.amount(),
                request.pin(),
                initialTtl
        );

        // Hand packet to Alice's offline phone
        simulatorService.injectPacket("phone-alice", packet);
        return ResponseEntity.ok(packet);
    }

    /**
     * Steps one round of Bluetooth gossip across all virtual devices.
     */
    @PostMapping("/mesh/gossip")
    public ResponseEntity<Map<String, Object>> runGossip() {
        return ResponseEntity.ok(simulatorService.runGossipRound());
    }

    /**
     * Simulates bridge devices with internet walking outside and uploading packets to backend.
     */
    @PostMapping("/mesh/flush")
    public ResponseEntity<List<IngestResult>> flushBridges() {
        return ResponseEntity.ok(simulatorService.flushBridges());
    }

    /**
     * Resets the entire mesh, accounts, and idempotency cache.
     */
    @PostMapping("/mesh/reset")
    public ResponseEntity<Map<String, String>> resetMesh() {
        simulatorService.resetMesh();
        return ResponseEntity.ok(Map.of(
                "status", "RESET_SUCCESS",
                "message", "Mesh packets cleared, account balances restored, idempotency cache flushed."
        ));
    }
}
