# Design System & UI Specification — GhostPay

**Scope**: Interactive Simulation Dashboard & Wire Protocol Aesthetics  
**Theme**: Modern High-Trust Fintech / Cyberpunk Dark Mode  
**Palette Standard**: High-Contrast Accessibility (WCAG AAA)  

---

## 1. Visual Language & Color Palette

The GhostPay dashboard is engineered for high-impact technical demonstrations and portfolio walkthroughs. It uses an enterprise dark-mode theme:

| Token Name | Hex Code | Visual Meaning / Component Use |
|---|---|---|
| **Background Dark** | `#090D16` | Main window background; deep slate black. |
| **Surface Card** | `#111827` | Component containers, device nodes, account cards. |
| **Border Muted** | `#1F2937` | Card borders, dividers, subtle separators. |
| **Settled Emerald** | `#10B981` | Successful settlement, verified GCM tag, online bridge. |
| **Gossip Amber** | `#F59E0B` | In-flight packets, mesh hop decrements, gossip rounds. |
| **Violation Rose** | `#EF4444` | Tampered ciphertexts, duplicate drops, expired TTLs. |
| **Crypto Violet** | `#8B5CF6` | RSA-2048 key badges, SHA-256 fingerprint pills. |
| **Text Primary** | `#F9FAFB` | Headings, monetary amounts, settled balances. |
| **Text Muted** | `#9CA3AF` | Subtitles, labels, transaction timestamps. |

---

## 2. Typography & Formatting Standards

* **Display & Balances**: Sans-serif (`Inter`, `system-ui`).
  * Currency formatted strictly with Rupee symbol and 2 decimal places: `₹500.00`.
* **Technical Identifiers**: Monospaced font (`JetBrains Mono`, `Fira Code`, `ui-monospace`).
  * Used for:
    * VPAs (`alice@ghostpay`, `bob@ghostpay`)
    * SHA-256 Hashes (`a3f8c9...710b`)
    * Nonces (`550e8400-e29b-41d4-a716-446655440000`)
    * Base64 Public Key Badges

---

## 3. Node Visual State Matrix

In the mesh visualizer, virtual devices display dynamic statuses:

```
┌─────────────────────────────────────────────────────────┐
│                      DEVICE CARDS                       │
├──────────────────────────┬──────────────────────────────┤
│ [Offline Phone (Alice)]  │ Border: Slate (#374151)      │
│ Zero connectivity        │ Icon: 📴 Offline             │
│ Holding: 1 Packet        │ Badge: Amber (TTL: 5)        │
├──────────────────────────┼──────────────────────────────┤
│ [Intermediate (Stranger)]│ Border: Slate (#374151)      │
│ Relaying packet          │ Icon: 📶 Hop Peer            │
│ Holding: 1 Packet        │ Badge: Amber (TTL: 4)        │
├──────────────────────────┼──────────────────────────────┤
│ [Bridge Node (Charlie)]  │ Border: Emerald (#10B981)    │
│ Walks into 4G coverage   │ Icon: 📡 4G Connected (Pulse)│
│ Ready to Ingest          │ Button: "Upload to Cloud"    │
└──────────────────────────┴──────────────────────────────┘
```

---

## 4. The 4-Step Interactive Action Ribbon

The dashboard interface organizes the demo flow into four intuitive actions:

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             GHOSTPAY CONTROL RIBBON                              │
├───────────────────┬────────────────────┬─────────────────────┬───────────────────┤
│ [ 1. 📤 Inject ]  │ [ 2. 🔄 Gossip ]   │ [ 3. 📡 Bridge ]    │ [ 4. ⚡ Dedupe ]  │
│ Creates payment   │ Broadcasts packet  │ Bridge uploads to   │ Simulates 3       │
│ Alice ➔ Bob ₹500  │ Decrements TTL     │ /api/bridge/ingest  │ simultaneous      │
│ Encrypts & queues │ All nodes receive  │ Settles in DB       │ bridge uploads    │
└───────────────────┴────────────────────┴─────────────────────┴───────────────────┘
```

---

## 5. Live Dashboard Widgets

1. **Account Balances Matrix**:
   * Shows each user, their VPA, current balance (`₹`), and JPA `@Version` counter.
   * Flashes emerald when balance updates.
2. **Real-Time Mesh Topology Grid**:
   * Live cards for all virtual devices showing packet counts and connection states.
3. **Ledger Stream (Top 20 Transactions)**:
   * Displays transaction ID, truncated SHA-256 `packetHash`, status pill (`SETTLED`, `DUPLICATE_DROPPED`, `INVALID_SIGNATURE`), bridge node ID, and settled timestamp.
4. **Cryptographic Inspector**:
   * Displays the server's live RSA-2048 Public Key in Base64 with a 1-click copy button.
