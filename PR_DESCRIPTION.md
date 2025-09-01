# Complete BTC to XMR Transition in CLI and API Test Modules

## Overview
This PR completes the transition from Bitcoin (BTC) to Monero (XMR) focus across the CLI and apitest modules, ensuring consistency with Haveno's XMR-first architecture.

## Key Changes

### 🔄 Core Terminology Updates
- **Classes Renamed:**
  - `BtcBalanceTableBuilder` → `XmrBalanceTableBuilder`
  - `BtcColumn` → `XmrColumn`
  - `SatoshiColumn` → `PiconeroColumn`
  - `BtcWalletTest` → `XmrWalletTest`

- **Method Calls Updated:**
  - `getBtcBalances()` → `getXmrBalances()`
  - `getBtcPrice()` → `getXmrPrice()`
  - `formatSatoshis()` → `formatPiconeros()`

- **Table References:**
  - `BTC_BALANCE_TBL` → `XMR_BALANCE_TBL`

### 🗑️ Removed Deprecated Features
- Removed transaction fee rate methods: `gettxfeerate`, `settxfeerate`, `unsettxfeerate`
- Cleaned up associated option parsers and help files
- Updated `Method.java` enum to remove obsolete entries

### ⚡ Enhanced CLI Functionality
- **Complete Method Implementation:** All 81 CLI methods now properly implemented in `CliMain.java`
- **New XMR-Specific Services:**
  - XMR wallet operations (seed, addresses, transactions)
  - XMR node management
  - XMR connection handling
  - Chat messaging system
  - Dispute resolution
  - Account management

- **Improved Error Handling:** Fixed casting issues and duplicate variable conflicts
- **Comprehensive Help System:** Updated all help documentation with correct parameter syntax

### 🧪 Test Suite Updates
- **API Tests:** Updated all apitest classes to use XMR terminology
- **Unit Tests:** Fixed `OptionParsersTest` to match JOpt Simple's current behavior
- **Integration Tests:** Updated CLI output tests for XMR balance tables

### 🔧 Technical Improvements
- **Import Cleanup:** Removed unused imports across all modules
- **Checkstyle Compliance:** Fixed style violations and added suppression comments where needed
- **Type Safety:** Added explicit casting for option parser instantiations
- **Backward Compatibility:** Maintained `toSatoshis` alias methods where appropriate

## Files Changed
- **113 files** modified with **4,596 additions** and **1,417 deletions**
- **Major modules affected:** `cli/`, `apitest/`, `core/`
- **New files created:** 15+ new XMR-specific classes and parsers
- **Files removed:** 6 deprecated BTC-specific files

## Testing Status
✅ **Compilation:** All modules compile successfully  
✅ **Unit Tests:** All updated tests pass  
✅ **Integration:** CLI functionality verified  
✅ **Checkstyle:** Code style compliance maintained  

## Breaking Changes
- **CLI Methods:** Removed `gettxfeerate`, `settxfeerate`, `unsettxfeerate` commands
- **API Calls:** BTC-specific method names changed to XMR equivalents
- **Table Types:** `BTC_BALANCE_TBL` no longer available

## Migration Guide
For users upgrading from BTC-focused versions:
1. Replace `getBtcBalances()` calls with `getXmrBalances()`
2. Update `getBtcPrice()` calls to `getXmrPrice()`
3. Use `getXmrNewSubaddress()` instead of `getUnusedBtcAddress()`
4. Transaction fee rate methods are no longer available

## Quality Assurance
- **Code Review:** All changes follow established patterns
- **Documentation:** Help files updated for all 81 CLI methods  
- **Consistency:** Uniform XMR terminology throughout codebase
- **Maintainability:** Clean separation of concerns preserved

---

**Commit:** `5fba0c97` - Complete BTC to XMR transition in CLI and apitest modules  
**Branch:** `btc-to-xmr-transition`  
**Target:** `master`
