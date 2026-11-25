package com.ghostpay.service;

import com.ghostpay.crypto.HybridCryptoService;
import com.ghostpay.crypto.ServerKeyHolder;
import com.ghostpay.domain.Account;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.domain.PaymentInstruction;
import com.ghostpay.domain.Transaction;
import com.ghostpay.domain.TransactionStatus;
import com.ghostpay.repository.AccountRepository;
import com.ghostpay.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BridgeIngestionServiceTest {

    @Autowired
    private BridgeIngestionService bridgeIngestionService;

    @Autowired
    private DemoService demoService;

    @Autowired
    private HybridCryptoService cryptoService;

    @Autowired
    private ServerKeyHolder serverKeyHolder;

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
    @DisplayName("Valid payment instruction settles atomically")
    void shouldSettleValidPayment() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("200.00"), "1234", 5
        );

        IngestResult result = bridgeIngestionService.ingest(packet, "bridge-charlie", 2);

        assertEquals("SETTLED", result.outcome());
        assertNotNull(result.transactionId());
        assertNotNull(result.packetHash());

        Account alice = accountRepository.findByVpa("alice@ghostpay").orElseThrow();
        assertEquals(0, new BigDecimal("800.00").compareTo(alice.getBalance()));
    }

    @Test
    @DisplayName("Tampered ciphertext is rejected as INVALID due to AEAD tag verification failure")
    void shouldRejectTamperedCiphertext() {
        MeshPacket validPacket = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("100.00"), "1234", 5
        );

        // Flip a byte in the encrypted ciphertext
        byte[] bytes = Base64.getDecoder().decode(validPacket.ciphertext());
        bytes[bytes.length - 1] ^= 0x55;
        String tamperedCiphertext = Base64.getEncoder().encodeToString(bytes);

        MeshPacket tamperedPacket = new MeshPacket(
                validPacket.packetId(), validPacket.ttl(), validPacket.createdAt(), tamperedCiphertext
        );

        IngestResult result = bridgeIngestionService.ingest(tamperedPacket, "bridge-rogue", 1);

        assertEquals("INVALID", result.outcome());
        assertNull(result.transactionId());
        assertTrue(result.reason().contains("Cryptographic"));
    }

    @Test
    @DisplayName("Expired packet older than 24 hours is rejected as EXPIRED")
    void shouldRejectExpiredPacket() {
        long twentyFiveHoursAgo = Instant.now().minusSeconds(25 * 3600).toEpochMilli();

        PaymentInstruction staleInstruction = new PaymentInstruction(
                "alice@ghostpay",
                "bob@ghostpay",
                new BigDecimal("50.00"),
                DemoService.hashPin("1234"),
                UUID.randomUUID().toString(),
                twentyFiveHoursAgo
        );

        String ciphertext = cryptoService.encrypt(staleInstruction, serverKeyHolder.getPublicKey());
        MeshPacket stalePacket = new MeshPacket(UUID.randomUUID().toString(), 3, twentyFiveHoursAgo, ciphertext);

        IngestResult result = bridgeIngestionService.ingest(stalePacket, "bridge-delayed", 4);

        assertEquals("EXPIRED", result.outcome());
        assertNull(result.transactionId());
    }

    @Test
    @DisplayName("Insufficient funds records failed transaction and does not alter account balance")
    void shouldFailWhenInsufficientFunds() {
        // Alice only has ₹1000, attempt to send ₹5000
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("5000.00"), "1234", 5
        );

        IngestResult result = bridgeIngestionService.ingest(packet, "bridge-1", 1);

        assertEquals("FAILED", result.outcome());
        assertNull(result.transactionId());
        assertTrue(result.reason().contains("Insufficient balance"));

        Account alice = accountRepository.findByVpa("alice@ghostpay").orElseThrow();
        assertEquals(0, new BigDecimal("1000.00").compareTo(alice.getBalance()));
    }

    @Test
    @DisplayName("Invalid UPI PIN records failure and does not settle")
    void shouldFailOnInvalidPin() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("100.00"), "9999", 5
        );

        IngestResult result = bridgeIngestionService.ingest(packet, "bridge-1", 1);

        assertEquals("FAILED", result.outcome());
        assertNull(result.transactionId());
        assertTrue(result.reason().contains("PIN"));
    }

    @Test
    @DisplayName("Self-transfer with identical sender and receiver VPA is rejected")
    void shouldRejectSelfTransfer() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "alice@ghostpay", new BigDecimal("50.00"), "1234", 5
        );

        IngestResult result = bridgeIngestionService.ingest(packet, "bridge-self", 1);

        assertEquals("FAILED", result.outcome());
        assertNull(result.transactionId());
        assertTrue(result.reason().contains("Self-transfer not permitted"));
    }

    @Test
    @DisplayName("Payment to non-existent receiver records failure with ACCOUNT_NOT_FOUND")
    void shouldFailWhenReceiverDoesNotExist() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "ghost_user@ghostpay", new BigDecimal("50.00"), "1234", 5
        );

        IngestResult result = bridgeIngestionService.ingest(packet, "bridge-1", 1);

        assertEquals("FAILED", result.outcome());
        assertNull(result.transactionId());
        assertTrue(result.reason().contains("Receiver account does not exist"));

        Transaction tx = transactionRepository.findByPacketHash(result.packetHash()).orElseThrow();
        assertEquals(TransactionStatus.ACCOUNT_NOT_FOUND, tx.getStatus());
    }
}
