package com.ghostpay.web;

import com.ghostpay.domain.Account;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.repository.AccountRepository;
import com.ghostpay.repository.TransactionRepository;
import com.ghostpay.service.DemoService;
import com.ghostpay.service.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BridgeControllerIntegrationTest {

    @Autowired
    private BridgeController bridgeController;

    @Autowired
    private DemoService demoService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        idempotencyService.reset();

        Account alice = accountRepository.findByVpa("alice@ghostpay").orElseGet(() ->
                Account.builder()
                        .vpa("alice@ghostpay")
                        .ownerName("Alice")
                        .balance(new BigDecimal("1000.00"))
                        .pinHash(DemoService.hashPin("1234"))
                        .build()
        );
        alice.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(alice);

        Account bob = accountRepository.findByVpa("bob@ghostpay").orElseGet(() ->
                Account.builder()
                        .vpa("bob@ghostpay")
                        .ownerName("Bob")
                        .balance(new BigDecimal("500.00"))
                        .pinHash(DemoService.hashPin("1234"))
                        .build()
        );
        bob.setBalance(new BigDecimal("500.00"));
        accountRepository.save(bob);
    }

    @Test
    @DisplayName("POST /api/bridge/ingest processes valid packet and returns SETTLED outcome")
    void shouldIngestValidPacketViaController() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("100.00"), "1234", 5
        );

        ResponseEntity<IngestResult> response = bridgeController.ingest(packet, "phone-bridge-test", 2);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("SETTLED", response.getBody().outcome());
        assertNotNull(response.getBody().transactionId());
    }

    @Test
    @DisplayName("POST /api/bridge/ingest drops duplicate upload on second attempt")
    void shouldDropDuplicatePacketViaController() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("50.00"), "1234", 5
        );

        // First upload succeeds
        ResponseEntity<IngestResult> first = bridgeController.ingest(packet, "phone-bridge-1", 1);
        assertEquals("SETTLED", first.getBody().outcome());

        // Second upload of identical packet must be dropped
        ResponseEntity<IngestResult> second = bridgeController.ingest(packet, "phone-bridge-2", 2);
        assertEquals("DUPLICATE_DROPPED", second.getBody().outcome());
    }
}
