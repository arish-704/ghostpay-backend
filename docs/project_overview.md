# GhostPay — Master System Brief & LLM Onboarding Guide

> **Notice for Engineers & AI Models**: This document is the single-source-of-truth briefing for the **GhostPay** project. It details the problem, system mechanics, exact cryptographic constants, technology stack, architecture layout, and current implementation progress so any developer or LLM can pick up this codebase and continue work with zero ambiguity.

---

## 1. What Are We Building? (The Elevator Pitch)

**GhostPay** is a high-performance Spring Boot backend and virtual simulator demonstrating **offline UPI (Unified Payments Interface) transactions routed through an opportunistic, device-to-device mesh network**.

### The Scenario:
* Alice is in a deep basement with **zero cellular/internet connectivity** (0 bars).
* Alice wants to pay Bob ₹500.
* Alice’s phone signs and encrypts the payment instruction with the bank server's public key.
* Alice broadcasts the encrypted packet over local Bluetooth Low Energy / Wi-Fi Direct.
* The packet hops from stranger to stranger across the physical basement.
* Eventually, Charlie (a stranger carrying the packet) walks outside, gets 4G/5G, and his phone **silently and opportunistically uploads the packet to the GhostPay backend**.
* The backend decrypts, verifies authenticity, drops any duplicate deliveries, checks temporal freshness, and settles the transaction atomically on the bank ledger.

---

## 2. What Problems Does This Solve?

### Problem 1: Untrusted Intermediaries & Privacy
* **Threat**: Random strangers’ phones carry Alice's payment packet. Intermediaries must not be able to read who is paying whom, read the amount, or change the recipient to themselves.
* **Solution**: **Hybrid Cryptography (RSA-OAEP + AES-256-GCM)**. The packet is locked with the server's public key. Intermediate devices see only opaque binary ciphertext. AES-GCM provides Authenticated Encryption with Associated Data (AEAD); flipping even 1 bit in transit triggers an `AEADBadTagException` during decryption, immediately rejecting tampered packets.

### Problem 2: The Duplicate Storm (Concurrent Bridge Delivery)
* **Threat**: In a physical mesh, 3 or more bridge devices might hold the exact same packet. When all 3 walk outside into 4G range at the exact same second, all 3 simultaneously POST the packet to the backend. If processed naively, Alice would be debited ₹1500 instead of ₹500!
* **Solution**: **Atomic Compare-And-Set on Ciphertext Hash**. Before decrypting, the server computes `SHA-256(ciphertext)` and claims it atomically via `ConcurrentHashMap.putIfAbsent(hash, timestamp)` (the Java equivalent of Redis `SETNX`). The first thread claims the hash; subsequent threads are short-circuited as `DUPLICATE_DROPPED` in nanoseconds. Additionally, the database has a `UNIQUE` index on `packet_hash` as a defense-in-depth shield.

### Problem 3: Replay Attacks
* **Threat**: An attacker captures Alice's valid encrypted packet and resubmits it 3 weeks later to debit her again.
* **Solution**: **Temporal Freshness Window + Nonce**. Inside the encrypted payload, Alice includes a random `UUID` nonce and a `signedAt` epoch timestamp. The server strictly rejects packets where `(SystemTime.now() - signedAt) > 24 hours`. Alice legitimately sending Bob ₹100 twice produces different nonces, generating different ciphertexts and hashes.

---

## 3. Technology Stack & Tooling

| Component | Technology | Rationale |
|---|---|---|
| **Language** | **Java 21 / 23** | Modern Java LTS with native records, virtual thread readiness, and `HexFormat`. |
| **Framework** | **Spring Boot 3.2.5** | Production-grade LTS framework for REST APIs and transaction orchestration. |
| **Persistence** | **Spring Data JPA & Hibernate 6** | Declarative repositories, dirty-checking, and optimistic locking (`@Version`). |
| **Local Database** | **H2 Database (In-Memory)** | Zero-install, embedded relational database with web console at `/h2-console`. |
| **Cloud Database** | **PostgreSQL** | Activated automatically via cloud environment variables on Render / Supabase. |
| **Boilerplate Reduction** | **Lombok** | Clean entities with `@Getter`, `@Setter`, `@Builder`, `@AllArgsConstructor`. |
| **Validation** | **Jakarta Bean Validation** | Declarative input validation (`@NotBlank`, `@DecimalMin`, `@NotNull`). |
| **Cryptography** | **Native Java Security (`javax.crypto`)** | Hardware-accelerated AES-NI and RSA implementations with zero external crypto bloat. |
| **Testing** | **JUnit 5 & AssertJ** | Concurrency test harness with `CountDownLatch` and `ExecutorService`. |

---

## 4. Master Table of Critical Numbers & Constants

These constants are the exact parameters governing the entire security, mesh, and financial pipeline. **Do not modify these without updating all corresponding layers:**

| Constant / Parameter | Exact Value | Location / Code Usage |
|---|---|---|
| **RSA Key Length** | `2048` bits (`256` bytes) | `ServerKeyHolder.java`, `ghostpay.crypto.key-size` |
| **RSA Transformation** | `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` | `HybridCryptoService.java` |
| **AES Key Length** | `256` bits (`32` bytes) | `HybridCryptoService.java` (`AES_KEY_SIZE_BITS`) |
| **AES Transformation** | `AES/GCM/NoPadding` | `HybridCryptoService.java` (`AES_GCM_TRANSFORMATION`) |
| **AES-GCM IV Length** | `12` bytes (`96` bits) | `HybridCryptoService.java` (`GCM_IV_LENGTH_BYTES`) |
| **AES-GCM Auth Tag Length** | `128` bits (`16` bytes) | `HybridCryptoService.java` (`GCM_TAG_LENGTH_BITS`) |
| **Min Ciphertext Byte Length**| `284` bytes (`256 + 12 + 16`) | Minimum valid wire format check |
| **Packet Hash Algorithm** | `SHA-256` (`64` lowercase hex chars) | `HybridCryptoService.hashCiphertext()` |
| **Max Packet TTL (Hops)** | `10` | `application.yaml` (`ghostpay.mesh.packet-ttl-max`) |
| **Initial Default Packet TTL**| `5` | Set when injecting a new packet into mesh |
| **Packet Freshness Limit** | `24` hours (`86,400,000` ms) | `application.yaml` (`packet-freshness-hours`) |
| **Monetary DB Precision** | `NUMERIC(19, 4)` | `Account.java`, `Transaction.java` (`precision=19, scale=4`) |
| **Default Local Port** | `8080` | `server.port: ${PORT:8080}` |

---

## 5. Wire Format Byte Layout

The `MeshPacket.ciphertext` string is a Base64-encoded binary concatenation:

```
┌─────────────────────────────────┬─────────────┬─────────────────────────────────┐
│ Encrypted AES Key               │ GCM IV      │ AES-GCM Ciphertext + Auth Tag   │
│ (Exactly 256 bytes, RSA-OAEP)   │ (12 bytes)  │ (Variable length JSON + 16 B)   │
└─────────────────────────────────┴─────────────┴─────────────────────────────────┘
```

---

## 6. Project Directory Layout

```
ghostpay-backend/
├── pom.xml                                   # Spring Boot 3.2.5, Java 21, H2, Lombok, Validation
├── docs/                                     # System documentation suite
│   ├── prd.md                                # Product Requirements Document
│   ├── architecture.md                       # System Architecture & Ingestion Pipeline
│   ├── security.md                           # Cryptographic Threat Model
│   ├── database.md                           # Schema & Persistence Specifications
│   ├── code_style.md                         # Java 21+ Engineering Guidelines
│   ├── testing.md                            # Testing Pyramid & Concurrency Harness
│   ├── design_system.md                      # UI/UX & Visual Simulator Guidelines
│   └── project_overview.md                   # THIS Master Briefing Document
└── src/
    ├── main/
    │   ├── resources/
    │   │   └── application.yaml              # Environment-driven configs (port, H2, TTL, key-size)
    │   └── java/com/ghostpay/
    │       ├── GhostpayBackendApplication.java
    │       ├── domain/                       # Core Entities & Records
    │       │   ├── Account.java              # JPA Entity: balance invariant & @Version optimistic lock
    │       │   ├── Transaction.java          # JPA Entity: immutable ledger, unique idx on packetHash
    │       │   ├── TransactionStatus.java    # Enum: SETTLED, DUPLICATE_DROPPED, INVALID_SIGNATURE, etc.
    │       │   ├── PaymentInstruction.java   # Record: private cleartext (VPA, amount, pinHash, nonce)
    │       │   └── MeshPacket.java           # Record: public mesh envelope (packetId, ttl, ciphertext)
    │       ├── crypto/                       # Cryptographic Engine
    │       │   ├── ServerKeyHolder.java      # RSA-2048 keypair generation & Base64 public key export
    │       │   └── HybridCryptoService.java  # RSA-OAEP + AES-GCM encrypt/decrypt & SHA-256 hash
    │       ├── repository/                   # [Next Phase]
    │       │   ├── AccountRepository.java    # findByVpa, existsByVpa
    │       │   └── TransactionRepository.java# existsByPacketHash, findTop20ByOrderBySettledAtDesc
    │       ├── service/                      # [Next Phase]
    │       │   ├── IdempotencyService.java   # Atomic in-memory claim (ConcurrentHashMap)
    │       │   ├── SettlementService.java    # Isolated @Transactional debit/credit execution
    │       │   └── BridgeIngestionService.java # The 5-stage ingestion coordinator
    │       ├── simulator/                    # [Next Phase]
    │       │   ├── VirtualDevice.java        # Simulated mobile node state
    │       │   └── MeshSimulatorService.java # Multi-hop gossip broadcast engine
    │       └── web/                          # [Next Phase]
    │           ├── BridgeController.java     # POST /api/bridge/ingest
    │           ├── DemoController.java       # POST /api/demo/send, /api/mesh/gossip, /api/mesh/flush
    │           └── GlobalExceptionHandler.java # Centralized RFC 7807 JSON error responses
    └── test/java/com/ghostpay/
        └── crypto/
            └── HybridCryptoServiceTest.java  # Verified: 3/3 tests passing (roundtrip, tamper, SHA-256)
```

---

## 7. Current Project Status & Next Steps

### Completed Milestones:
* [x] Project initialized with Maven, Spring Boot 3.2.5, and Java 21/23.
* [x] Cloud-ready `application.yaml` with environment variable overrides (`${PORT:8080}`, `${SPRING_DATASOURCE_URL}`).
* [x] Git repository configured, `.gitignore` fortified with secret masks (`.env`, `*.env`), first commits pushed to GitHub.
* [x] Complete domain layer implemented: `Account` (with `@Version`), `Transaction` (with unique index), `TransactionStatus`, `PaymentInstruction` (Java record), and `MeshPacket` (Java record).
* [x] Cryptography engine completed: `ServerKeyHolder` (RSA-2048) and `HybridCryptoService` (RSA-OAEP + AES-256-GCM AEAD).
* [x] Cryptographic test suite passed: `HybridCryptoServiceTest` (3 tests pass in < 1 second).
* [x] Documentation suite: 8 comprehensive architectural documents generated in `docs/`.

### What Comes Next (Roadmap):
1. **Phase 4: Repository Layer**: Connect `AccountRepository` and `TransactionRepository`.
2. **Phase 5: Service Layer**:
   * `IdempotencyService` (Atomic CAS on SHA-256 hashes).
   * `SettlementService` (Atomic `@Transactional` ledger writes).
   * `BridgeIngestionService` (The 5-stage ingestion pipeline).
3. **Phase 6: Simulation Engine**:
   * `VirtualDevice` & `MeshSimulatorService` (Gossip rounds, virtual device states).
4. **Phase 7: Web / REST Layer & Global Exception Handler**:
   * Ingestion endpoint (`/api/bridge/ingest`), demo controls, unified error responses.
5. **Phase 8: Concurrency Stress Test & Real-Time Dashboard**:
   * `IdempotencyConcurrencyTest` (3-bridges-at-once race condition test).
   * Interactive dark-mode dashboard.
