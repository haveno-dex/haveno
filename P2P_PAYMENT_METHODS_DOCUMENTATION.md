# Haveno Native - P2P Payment Methods & Core Enhancements
## Technical Documentation for PR#2030, PR#2284, PR#1811, PR#1761

This document provides a comprehensive overview of the integration of missing P2P payment methods into the Haveno daemon and the critical stability fixes applied to the Haveno Desktop GUI.

---

## 1. P2P Payment Methods Integration
The Haveno core has been significantly expanded to support a wide range of global and regional payment systems, enabling decentralized trading across diverse markets.

### Supported Payment Methods (New & Updated)
The following methods are now fully exposed via the gRPC API and integrated into the Desktop GUI:

*   **Global Systems:** Western Union, Perfect Money, Advanced Cash, Paysera, Verse, Monese, Satispay.
*   **Latin America:** Pix (Brazil), Nequi (Colombia), Bizum (Spain).
*   **Asia:** UPI, PAYTM, NEFT, RTGS, IMPS (India), Japan Bank, Prompt Pay (Thailand).
*   **Banking & Fiat:** National Bank, Same Bank, Specific Banks, Domestic Wire Transfer, Cash Deposit, Hal Cash.
*   **Digital Wallets:** Money Beam, Pop Money, Tikkie, Capitual, Celpay.

### Technical Implementation Details

#### Dynamic Form Support (PR #2284)
Implemented `getInputFieldIds()` across 28 payment account classes. This change replaces legacy exceptions with a structured field retrieval system, allowing API clients to dynamically build user interfaces based on the daemon's requirements.

#### Protocol Buffer & Data Model (PR #1811, #1761)
*   Integrated the `ACCOUNT_NAME` field into payment account structures to improve user organization and identification.
*   Aligned gRPC protobuf definitions with the Desktop application's internal data models to ensure 1:1 functional parity.

#### Field Validation & Security
*   Added specialized validators for new field types: `BANK_CODE`, `IFSC`, `PROMPT_PAY_ID`, and `VIRTUAL_PAYMENT_ADDRESS`.
*   Removed the hard-coded whitelist in `PaymentMethod.getPaymentMethods()`, enabling access to all 57 supported payment methods through the gRPC interface.

---

## 2. Stability & Compatibility Fixes (Java 21 / JavaFX)
To ensure the Haveno Desktop application runs reliably on modern systems, several critical patches were applied to the core UI components.

### JavaFX NullPointer Defense
Rewrote `JFXTextFieldSkinHavenoStyle.java` and `JFXTextAreaSkinHavenoStyle.java` to implement defensive programming patterns. These components are now immune to `NullPointerException` during the layout pass, a common issue when running JFoenix components on Java 21.

### CSS Visual Alignment
Applied a master CSS patch to `haveno.css` to fix the "Double Prompt Text" bug and element overlapping.
*   Targeted `prompt-text-fill` with transparency to hide native JavaFX prompts while keeping JFoenix floating labels visible.
*   Adjusted vertical spacing (`vgap`) and margins to prevent UI elements from clipping or overlapping in high-density views like "Settings -> Preferences".

### Network & Connectivity
*   **Mainnet Seed Nodes:** Updated `xmr_mainnet.seednodes` with real, active Tor hidden service addresses to replace placeholders, ensuring instant P2P connectivity on startup.
*   **Resilient Parsing:** Hardened the `DefaultSeedNodeRepository` to gracefully handle malformed or empty seed node strings passed via command line arguments.

---

**Prepared by:** DeliriumXS
**Project:** Haveno Core Payment Methods Initiative
