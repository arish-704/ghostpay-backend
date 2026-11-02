# Product Requirements Document (PRD) — GhostPay

**Project Name**: GhostPay (Mesh-Routed Deferred Settlement Backend)  
**Document Version**: 1.0.0  
**Target Platform**: Java 21 / 23, Spring Boot 3.2.x, Cloud-Deployable (Render, Supabase, AWS)  
**Status**: Active Architecture & Implementation  

---

## 1. Executive Summary & Vision

### 1.1 The Problem
Unified Payments Interface (UPI) is the world's most successful real-time payment rail, processing billions of transactions monthly. However, **UPI is fundamentally built on synchronous cellular and internet connectivity**:
* In basements, underground parking facilities, metro tunnels, disaster scenarios, remote music festivals, or rural dead zones, internet connectivity drops to zero.
* Users in these zones are completely locked out of transacting digitally, forcing reliance on physical cash or informal credit.

### 1.2 The Solution: GhostPay
**GhostPay** is a mesh-routed, deferred-settlement payment protocol and backend engine. It enables offline peer-to-peer transactions in zero-connectivity environments:
1. **Offline Origin**: A sender offline encrypts a payment instruction using hybrid cryptography (RSA-OAEP + AES-256-GCM) with the server's public key.
2. **Device-to-Device Mesh Gossip**: The encrypted packet hops between nearby stranger devices via local peer-to-peer transport (BLE / Wi-Fi Direct).
3. **Opportunistic Cloud Bridge**: When any device in the mesh wanders into cellular (4G/5G) or Wi-Fi range, it acts as an unauthenticated bridge node and offloads the packet to the GhostPay backend.
4. **Atomic Backend Settlement**: The backend validates, decrypts, verifies cryptographic freshness, deduplicates concurrent uploads, and settles the transaction atomically.

---

## 2. User Personas & Scenarios

### Persona A: Basement Buyer (Alice)
* **Context**: Alice is in an underground parking lot with 0 bars of cellular service.
* **Goal**: Pay Bob ₹300 for a car wash.
* **Flow**: Alice enters Bob's VPA (`bob@ghostpay`), enters amount ₹300, and enters her PIN. Her device compiles the instruction, encrypts it into a `MeshPacket`, and broadcasts it locally.

### Persona B: Basement Merchant (Bob)
* **Context**: Bob operates his shop in the basement. He also has 0 connectivity.
* **Goal**: Receive confirmation that Alice signed the payment instruction.
* **Flow**: Bob's phone receives Alice's broadcasted packet over local mesh radio. Bob sees cryptographic proof of the signed envelope.

### Persona C: The Opportunistic Bridge (Charlie)
* **Context**: Charlie is a stranger walking up the stairs out of the basement. He doesn't know Alice or Bob.
* **Goal**: Seamless background relaying.
* **Flow**: Charlie's phone passively holds Alice's packet. When Charlie steps outside and gains 4G, his phone silently calls `POST /api/bridge/ingest`.

---

## 3. Functional Requirements (FR)

### FR-1: Payment Packaging & Hybrid Encryption
* The system shall generate a `PaymentInstruction` containing `senderVpa`, `receiverVpa`, `amount`, `pinHash`, `nonce`, and `signedAt`.
* The instruction must be encrypted using hybrid encryption:
  * JSON payload encrypted with a single-use random **AES-256-GCM** key.
  * The AES key encrypted with the backend's **RSA-2048 Public Key** using OAEP padding.
* The output wire format must be a `MeshPacket` carrying an opaque `ciphertext`, `packetId`, `createdAt`, and `ttl`.

### FR-2: Mesh Gossip Simulation & Propagation
* The backend shall include a simulator engine (`MeshSimulatorService`) managing virtual devices (`VirtualDevice`).
* Support stepping rounds of gossip: decrementing TTL per hop, preventing zombie loops when `ttl <= 0`.
* Track packet distribution across virtual devices to verify relay mechanics.

### FR-3: Ingestion & Deduplication (Idempotency)
* The ingestion endpoint (`POST /api/bridge/ingest`) shall accept packets from any bridge node.
* The backend must compute `SHA-256(ciphertext)` before decryption.
* The backend must atomically claim the hash (via `ConcurrentHashMap.putIfAbsent` / Redis `SETNX`).
* If the hash has already been processed or is currently in-flight, return `DUPLICATE_DROPPED` immediately without decrypting or touching the ledger.

### FR-4: Cryptographic Verification & Freshness Check
* The backend must decrypt the AES key using its private RSA key and verify AES-GCM authentication tag.
* If any bit in the ciphertext was tampered with, decryption must reject with `INVALID_SIGNATURE`.
* The backend must reject packets where `(SystemTime.now() - signedAt) > 24 hours` as `EXPIRED`.

### FR-5: Atomic Ledger Settlement
* Settlement must execute in an isolated database transaction (`@Transactional`).
* The sender's balance must be debited and the receiver's balance credited atomically.
* If the sender balance is insufficient, reject with `INSUFFICIENT_FUNDS` and record failed audit entry.
* Persist an immutable ledger entry in `Transaction` table with a unique index on `packetHash`.

---

## 4. Non-Functional Requirements (NFR)

### NFR-1: Security & Confidentiality
* **Zero Trust Intermediaries**: Untrusted bridge phones carry the packet. They must have zero ability to inspect amounts, senders, or recipients.
* **Tamper-Proof Integrity**: AES-GCM AEAD (Authenticated Encryption with Associated Data) guarantees bit-level tamper resistance.

### NFR-2: High Concurrency & Race Condition Defense
* **Duplicate Storm Handling**: The system must sustain 100+ concurrent requests delivering the identical packet simultaneously and guarantee **exactly-once execution**.
* **Optimistic Locking**: Account entities must utilize JPA `@Version` to prevent lost updates under heavy simultaneous ledger writes.

### NFR-3: Precision & Financial Correctness
* **No Floating-Point Arithmetic**: All monetary balances and transaction amounts must use Java `BigDecimal` with explicit scale (`precision = 19, scale = 4`).

### NFR-4: Cloud Portability & Zero-Config Local Dev
* Local development must run with embedded in-memory H2 database.
* Production deployments (Render, Supabase, Railway) must configure seamlessly via environment variables (`SPRING_DATASOURCE_URL`, `PORT`) without code modification.

---

## 5. Honest Trade-Offs & Out-of-Scope Boundaries

| Constraint | Architectural Reality |
|---|---|
| **Funds Availability at Point of Sale** | Purely offline senders cannot prove account balance without specialized secure element hardware (like UPI Lite wallets). GhostPay operates as **mesh-routed deferred settlement** (IOU relay). |
| **Offline Double Spending** | An offline sender with ₹500 balance could broadcast two ₹500 packets to different vendors. The first packet reaching the backend settles; the second fails at ledger commit. |
| **Physical Radio Layer** | Background BLE GATT on iOS/Android is OS-throttled. This project implements the complete cryptographic wire format and backend settlement pipeline, simulating the radio gossip layer. |
