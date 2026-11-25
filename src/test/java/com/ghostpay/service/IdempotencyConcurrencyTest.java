package com.ghostpay.service;

import com.ghostpay.domain.Account;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private BridgeIngestionService bridgeIngestionService;

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

        // Ensure accounts exist with clean balances
        Account alice = accountRepository.findByVpa("alice@ghostpay").orElseGet(() ->
                Account.builder()
                        .vpa("alice@ghostpay")
                        .ownerName("Alice Sharma")
                        .balance(new BigDecimal("1000.00"))
                        .pinHash(DemoService.hashPin("1234"))
                        .build()
        );
        alice.setBalance(new BigDecimal("1000.00"));
        accountRepository.save(alice);

        Account bob = accountRepository.findByVpa("bob@ghostpay").orElseGet(() ->
                Account.builder()
                        .vpa("bob@ghostpay")
                        .ownerName("Bob Verma")
                        .balance(new BigDecimal("500.00"))
                        .pinHash(DemoService.hashPin("1234"))
                        .build()
        );
        bob.setBalance(new BigDecimal("500.00"));
        accountRepository.save(bob);
    }

    @Test
    @DisplayName("Single packet delivered simultaneously by 3 bridges must settle exactly once")
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws InterruptedException {
        // 1. Create a single payment packet: Alice sends Bob ₹500
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay",
                "bob@ghostpay",
                new BigDecimal("500.00"),
                "1234",
                5
        );

        int bridgeCount = 3;
        ExecutorService executor = Executors.newFixedThreadPool(bridgeCount);
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch finishSignal = new CountDownLatch(bridgeCount);

        List<IngestResult> results = Collections.synchronizedList(new ArrayList<>());

        // 2. Prepare 3 threads representing 3 bridge nodes
        for (int i = 0; i < bridgeCount; i++) {
            final String bridgeId = "phone-bridge-" + (i + 1);
            executor.submit(() -> {
                try {
                    startGun.await(); // Wait for all threads to align
                    IngestResult res = bridgeIngestionService.ingest(packet, bridgeId, 1);
                    results.add(res);
                } catch (Exception e) {
                    fail("Bridge thread failed with exception: " + e.getMessage());
                } finally {
                    finishSignal.countDown();
                }
            });
        }

        // 3. Fire all 3 threads simultaneously
        startGun.countDown();
        boolean completed = finishSignal.await(5, TimeUnit.SECONDS);
        assertTrue(completed, "All bridge uploads should complete within 5 seconds");
        executor.shutdown();

        // 4. Assert outcomes: Exactly 1 SETTLED, exactly 2 DUPLICATE_DROPPED
        long settledCount = results.stream().filter(r -> "SETTLED".equals(r.outcome())).count();
        long duplicateCount = results.stream().filter(r -> "DUPLICATE_DROPPED".equals(r.outcome())).count();

        assertEquals(1, settledCount, "Exactly ONE bridge upload must settle the payment");
        assertEquals(2, duplicateCount, "The other TWO concurrent uploads must be dropped as duplicates");

        // 5. Verify Ledger: Only 1 transaction record
        List<Transaction> transactions = transactionRepository.findAll();
        assertEquals(1, transactions.size(), "Ledger must contain exactly 1 transaction");
        assertEquals(TransactionStatus.SETTLED, transactions.get(0).getStatus());

        // 6. Verify Accounts: Alice debited ₹500 once (not ₹1500), Bob credited ₹500 once
        Account alice = accountRepository.findByVpa("alice@ghostpay").orElseThrow();
        Account bob = accountRepository.findByVpa("bob@ghostpay").orElseThrow();

        assertEquals(0, new BigDecimal("500.00").compareTo(alice.getBalance()),
                "Alice's balance must be debited exactly once: 1000 - 500 = 500");
        assertEquals(0, new BigDecimal("1000.00").compareTo(bob.getBalance()),
                "Bob's balance must be credited exactly once: 500 + 500 = 1000");
    }
}
