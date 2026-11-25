package com.ghostpay.web;

import com.ghostpay.domain.Account;
import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.domain.SendPaymentRequest;
import com.ghostpay.domain.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DemoControllerIntegrationTest {

    @Autowired
    private DemoController demoController;

    @Test
    @DisplayName("GET /api/server-key returns RSA-2048 public key Base64")
    void shouldReturnServerKey() {
        ResponseEntity<Map<String, String>> response = demoController.getServerKey();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("RSA-2048", response.getBody().get("algorithm"));
        assertTrue(response.getBody().containsKey("publicKeyBase64"));
    }

    @Test
    @DisplayName("GET /api/accounts returns seeded demo accounts")
    void shouldReturnAccounts() {
        ResponseEntity<List<Account>> response = demoController.getAccounts();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty(), "Accounts list must not be empty");
    }

    @Test
    @DisplayName("GET /api/mesh/state returns initial device network state")
    void shouldReturnMeshState() {
        ResponseEntity<List<Map<String, Object>>> response = demoController.getMeshState();
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(5, response.getBody().size(), "Should contain 5 virtual devices");
    }

    @Test
    @DisplayName("Complete end-to-end demo flow: send -> gossip -> flush -> transactions")
    void shouldExecuteFullDemoFlow() {
        // 1. Reset mesh
        ResponseEntity<Map<String, String>> resetRes = demoController.resetMesh();
        assertEquals(HttpStatus.OK, resetRes.getStatusCode());

        // 2. Alice sends Bob ₹150
        SendPaymentRequest request = new SendPaymentRequest(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("150.00"), "1234"
        );
        ResponseEntity<MeshPacket> sendRes = demoController.sendPayment(request);
        assertEquals(HttpStatus.OK, sendRes.getStatusCode());
        assertNotNull(sendRes.getBody());
        assertNotNull(sendRes.getBody().ciphertext());

        // 3. Run gossip round
        ResponseEntity<Map<String, Object>> gossipRes = demoController.runGossip();
        assertEquals(HttpStatus.OK, gossipRes.getStatusCode());
        assertEquals("GOSSIP_ROUND_COMPLETED", gossipRes.getBody().get("status"));

        // 4. Flush bridges to settle in backend
        ResponseEntity<List<IngestResult>> flushRes = demoController.flushBridges();
        assertEquals(HttpStatus.OK, flushRes.getStatusCode());
        assertNotNull(flushRes.getBody());

        // 5. Verify transaction ledger endpoint
        ResponseEntity<List<Transaction>> txRes = demoController.getRecentTransactions();
        assertEquals(HttpStatus.OK, txRes.getStatusCode());
        assertNotNull(txRes.getBody());
        assertFalse(txRes.getBody().isEmpty(), "Ledger should record settled transaction");
    }
}
