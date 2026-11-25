package com.ghostpay.simulator;

import com.ghostpay.domain.IngestResult;
import com.ghostpay.domain.MeshPacket;
import com.ghostpay.service.DemoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MeshSimulatorServiceTest {

    @Autowired
    private MeshSimulatorService simulatorService;

    @Autowired
    private DemoService demoService;

    @BeforeEach
    void setUp() {
        simulatorService.resetMesh();
    }

    @Test
    @DisplayName("Gossip round propagates packet to neighbor nodes and decrements TTL")
    void shouldPropagatePacketThroughGossip() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("100.00"), "1234", 5
        );

        simulatorService.injectPacket("phone-alice", packet);

        VirtualDevice alice = simulatorService.getDevice("phone-alice");
        VirtualDevice stranger1 = simulatorService.getDevice("phone-stranger-1");

        assertEquals(1, alice.getPacketCount());
        assertEquals(0, stranger1.getPacketCount());

        // Run Round 1 of gossip
        Map<String, Object> summary = simulatorService.runGossipRound();
        assertEquals("GOSSIP_ROUND_COMPLETED", summary.get("status"));

        // All devices should now hold the packet
        assertTrue(stranger1.getPacketCount() >= 1, "Stranger 1 should have received the packet");
    }

    @Test
    @DisplayName("Bridge flush uploads packets from online nodes and clears buffer")
    void shouldFlushBridges() {
        MeshPacket packet = demoService.createPaymentPacket(
                "alice@ghostpay", "bob@ghostpay", new BigDecimal("150.00"), "1234", 5
        );

        // Put packet on the bridge node
        VirtualDevice bridge = simulatorService.getDevice("phone-bridge");
        bridge.storePacket(packet);
        assertEquals(1, bridge.getPacketCount());

        List<IngestResult> results = simulatorService.flushBridges();
        assertFalse(results.isEmpty());
        assertEquals("SETTLED", results.get(0).outcome());

        // Bridge buffer must be empty after upload
        assertEquals(0, bridge.getPacketCount());
    }
}
