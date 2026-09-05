# Application encryption migration

This change addresses the ECB/key-reuse problem in [#2344](https://github.com/haveno-dex/haveno/issues/2344)
and the password-guessing concern in [#1161](https://github.com/haveno-dex/haveno/issues/1161).
The old keyring does use PKCS#12 password-based cryptography; it relies on provider-selected
algorithms and iteration counts rather than an explicit memory-hard password policy.

The migration retains the account's 256-bit master key, RSA encryption identity, and DSA signing
identity. Offers, contracts, existing payment-account ciphertexts, and peer identities retain
their meaning. New private local writes use authenticated encryption. Network sending remains
legacy in this first release, as required for a staged rollout.

## Authenticated envelope

`AuthenticatedEncryption` uses AES-256-GCM with a 128-bit tag. Each envelope gets an independent
32-byte random HKDF salt and 12-byte random nonce from `SecureRandom`. HKDF-SHA256 derives a
per-envelope AES key from the supplied key. The purpose is bound in both HKDF's info and GCM's
associated data. Encryption and decryption use the existing Bouncy Castle dependency.

| Field | Bytes |
| --- | ---: |
| Format magic, hex `48564e0080454e43` | 8 |
| Version, `02` | 1 |
| HKDF salt | 32 |
| GCM nonce | 12 |
| Ciphertext | plaintext length |
| GCM tag | 16 |

HKDF info is ASCII `Haveno encryption v2 AES-256-GCM`, followed by the four-byte big-endian UTF-8
purpose length and the UTF-8 purpose. Associated data is the complete 53-byte header followed
by the same length and purpose. Purpose strings are protocol constants:

- `identity/sig.key` and `identity/enc.key` for identity keys;
- `account-master-key/argon2id-v13-m65536-t3-p4` for the password-wrapped master;
- `store/<logical filename>` and `append-log/<logical filename>` for persistence;
- `rpc-credential`, `p2p-message`, and `payment-account` for their respective payloads.

A recognised envelope with an unsupported version, wrong purpose, failed tag, or truncation
fails without selecting a legacy reader. Pre-versioned formats still need explicit legacy
readers. This is not a mechanism for preventing rollback, deletion, or replacement of an entire
profile with older data. Legacy plaintext persistence remains readable for upgrade compatibility.

Array readers authenticate before returning plaintext. Persistence uses two bounded-memory
passes: first verify to a discard sink, then parse while authenticating again. No parsed result
is published before the second pass reaches and verifies EOF. The streaming API's plaintext is
provisional before EOF and must not be used directly by network handlers. There are no plaintext
temporary files. Tests cross-check the format against an independent SunJCE/HKDF implementation
and exercise a 96 MiB stream with a 64 MiB heap.

## Account keys

`sym.key` replaces `sym.p12`. Its header is ASCII `HAVENOKEY`, version byte `02`, profile byte
`01`, and a 16-byte random Argon2 salt. The remaining bytes are an authenticated envelope of the
32-byte master key. The complete file is 128 bytes. The fixed profile uses Argon2id v1.3,
65,536 KiB memory, three passes, and four lanes, with a 32-byte result. This matches the
memory-constrained recommendation in [RFC 9106](https://www.rfc-editor.org/rfc/rfc9106.html).
The reader accepts only this profile and exact length before running the KDF. Changes to its
parameters require a new profile and an explicit reader; file bytes cannot request arbitrary
memory or iteration counts. Password characters are encoded as UTF-8 by Bouncy Castle. Existing
ASCII password restrictions remain in the account creation/change API for wallet compatibility.
Null passwords derive from the empty string and provide no protection from someone with the files.

Unlock first validates the legacy master and both identity keys. It then writes the new wrapper,
upgrades the identity files, and verifies recovery copies of all three keys before retiring the
old PKCS#12 wrapper and its known rolling copies. Each key replacement uses a same-directory temporary file, file sync, read-back
comparison, and atomic rename. Directory entries are synced on platforms supporting directory
FileChannels; Java does not provide this on Windows. Key replacement refuses a filesystem that
does not support atomic moves. An interrupted migration retains the same master and can resume.

`sym.p12` remains as a locked compatibility guard containing only an unrelated dummy key under a
discarded random password. It prevents old applications from mistaking the migrated directory
for a new account and overwriting its identity keys. The bundled guard contains no account secret.

The new wrapper is authoritative whenever present. Failed authentication never falls back to
`sym.p12` or a recovery copy. A partial key directory is an existing, damaged account, and cannot
silently generate a replacement identity. `sym.key.bak`, `sig.key.bak`, and `enc.key.bak` support
deliberate recovery. If a live key is damaged, stop the application and restore its verified
recovery copy under the original filename; do not delete the directory or create a new account
over it. Completed migrations do not rewrite unchanged keys on login. Missing or outdated recovery
copies are repaired when possible; failure to repair them does not block an otherwise valid unlock.
Interrupted initial creation also preserves partial files. If that profile has never been used,
retry creation in a new empty profile directory; partial files are never automatically discarded
because they can also represent an existing account whose keys were damaged or deleted.

## Persistence and credentials

Initialized private stores upgrade after successful decryption, protobuf parsing, and domain conversion.
Migration writes the parsed envelope to a verified temporary encrypted file and retains a rolling
copy before replacement. A failed upgrade write leaves successfully read data available and defers
migration to a later read or write. An unreadable required account store stays in place, blocks writes
by its persistence manager, and stops startup with a desktop error or a nonzero headless exit status,
without flushing persistence. It is not treated as an absent store. Startup must be retried after recovery.
Rebuildable network caches and the last desktop navigation path explicitly
retain quarantine-and-rebuild recovery. A damaged legacy closed-trade file is retained without
hiding history already recovered from the append log. Large stores are not buffered as complete
ciphertext or plaintext byte arrays, and their file I/O does not hold the snapshot/queue monitor.

Append logs accept legacy and new records. Fully authenticated replay migrates legacy records,
preserving record order and bytes. Rewrite verifies the new records before replacing the log.
A failed optional migration leaves the replayed history available; subsequent appends revalidate
the live file and can add new records without requiring the legacy frames to migrate first.
A failed authentication stops replay and preserves the entire log. Existing torn-tail and invalid
length-prefix recovery retains a timestamped copy before truncation. The format does not add
cross-record ordering or rollback authentication.
Before truncating the main closed-trade log, Haveno syncs the retained backup and writes
`ClosedTrades.log.recovery-needed`. This marker keeps history incomplete across restarts and
later appends or rewrites; the warning identifies the first unresolved backup. If the backup or marker
cannot be saved, recovery fails without truncating the live log. Existing historical backups
without a marker do not disable cleanup.

To reconcile incomplete history, stop Haveno and back up the complete profile first. Inspect the
retained log suffix and all later timestamped copies of the same log in `backup_of_corrupted_data/`.
The marker keeps its first reference when another truncation occurs; the later backups must also
be reconciled. Reconcile recoverable upserts and tombstones with pending/failed trades, wallet
state, and later activity. A torn encrypted frame may be unreadable, so copying bytes back
is not sufficient; verify which trades should be active or closed and retain their latest state.
Restarting while the marker remains is safe: automatic destructive cleanup stays disabled.
Only after reconciliation, stop Haveno, remove `ClosedTrades.log.recovery-needed`, and restart
to allow complete replay and cleanup. Keep the recovery backup. Do not remove the marker merely
to dismiss the warning, or restore an old log over newer activity without reconciliation.

Reopening a closed trade increments its persisted reopen count before writing the pending store.
Only a successful durable pending write permits the closed tombstone, and a delayed callback
cannot tombstone a later close or reopen. A newer reopen count wins when duplicate copies exist;
equal counts retain the existing store priority. Incomplete history preserves both stored copies.
Legacy trades start at count zero. An already-interrupted move from an older build is resolved
by the existing store priority: complete history keeps the closed copy, while incomplete history
initializes the active copy and retains both. The counter cannot reconstruct an unrecorded past
intent; a reopen lost under the complete-history rule may need to be repeated after reconciliation.

Pending closed-trade records are recovered before main-log replay or writes, so a failed replay
cannot let later retries replace earlier pending records. An unreadable pending log stays in place;
new records can still append to a healthy main log, preserving the existing write behavior.
Pending replay requires complete frames and never truncates damaged queue bytes. If
`ClosedTrades.log.pending` cannot be read, authenticated main-log history remains visible with
an incomplete-history warning. Legacy merging, compaction, stored duplicate removal, mailbox
cleanup, and automatic sensitive-data clearing for closed trades are deferred. Initialization prefers the
newer reopen count, or an active copy when counts match, without deleting either stored copy. These protections
remain until a complete replay after recovery and restart, even if a write retry later succeeds.
A partial pending rewrite left by a failed first write is also preserved for recovery if the
process exits before the queued records reach the main log. A successful main-log retry clears
both the pending queue and any leftover rewrite temporary file, preventing stale replay.
Failed queue clears are retried on later appends, on the retry timer, and at shutdown. The main-log
append and pending-queue clear are separate durable writes. Mutation IDs let recovery discard
pending records already committed to the main log, so stale retries cannot overwrite newer updates.
Legacy pending records without mutation IDs cannot have their earlier commit status verified.
If both logs are unavailable, newer queued entries remain memory-only and are lost on exit unless
recovery succeeds. Recovering an unreadable pending log still requires reconciling previously
uncommitted or unidentified older records with any newer main-log entries before replay.

RPC credential list version zero retains its old scrypt/password-or-plaintext rules for reading.
Version one always encrypts credentials with the account master and `rpc-credential` purpose,
including accounts without a password. All entries are validated and converted before the live
list is replaced. Startup waits for a successful forced write of the migrated credentials, and
password changes are rejected until startup completes. This prevents a password change and crash
from leaving old credentials readable only under a retired password. RPC credentials no longer
need re-encryption on password changes. The outer private store is independently authenticated.

Restore a backup under its original logical filename, because that name is authenticated.
Financial data backups and exported profiles are retained; historical copies may use older
cryptography or passwords. Replacing live files cannot revoke external backups or guarantee
physical erasure on SSDs, snapshots, or copy-on-write filesystems.

## Release sequence and limits

1. Ship this reader/migration release as an optional update with `Version.NETWORK_ENCRYPTION_VERSION = 1`. Both sealed
   messages and payment-account payloads still send legacy bytes. Incoming paths accept either
   scheme, and the existing ciphertext signature and payment-account contract hash are verified.
2. Include these readers in the next mandatory security release with
   [#2577](https://github.com/haveno-dex/haveno/pull/2577). Establish a minimum compatible version,
   upgrading seeds and arbitrators first. Keep version one sending during the transition if needed.
   A minimum-version filter checks the local application version; it does not evict old nodes or
   upgrade offline mailbox recipients, and it can also block settlement and dispute actions.
3. Enable version two sending once recipients, including participants in existing trades and
   disputes, have compatible readers. This can ship in the mandatory release if readiness is
   established, or in a later optional update under the same minimum version. It need not force
   a second network upgrade. A new-trade version gate or elapsed grace period alone is insufficient.
   Retain legacy readers for historical contracts and messages. There is no unauthenticated peer
   negotiation or automatic fallback after a failed new-format decryption.

This release also recovers addressed mailbox messages that an older reader marked undecryptable.
The receiver retries them despite a persisted ignored UID, and replaces the stored foreign entry
after successful signature and ciphertext verification. Other recipients' failures remain cached.
The same recovery works with version one or version two sending; changing the sending constant
does not require another cache migration or test rewrite. Old recipients still need to upgrade
before they can process version two messages; retries do not make old readers compatible.

Existing signed contracts and legacy payment-account ciphertexts remain usable after their
participants upgrade; they do not all need to be closed before version two sending. Contracts
still being negotiated across the security release's multisig-contract change need separate
coordination because old peers cannot sign the new contract format.

Before the optional update, stop Haveno and make a complete, consistent backup of the profile,
including its keys, stores, wallets, and network identity. Upgraded private files are not readable
by old applications. Downgrading requires restoring that complete pre-upgrade profile, not just
old key files. If trading or other financial activity occurred after the backup, reconcile that
activity before using the restored profile; restoring a backup does not undo network or wallet
activity. Automatic key recovery copies are not a pre-upgrade profile backup.
These formats intentionally do not implement the unpublished CTR/HMAC format in PR #2436.

The upgrade itself does not rotate Monero wallet passwords. The existing password-change API
still rotates several wallets through listeners and is not a crash-atomic multiwallet transaction.
This change performs the wrapper's memory-hard KDF and verification before those listeners run,
and reports a committed live-wrapper change even if subsequent backup maintenance fails.
The desktop performs password changes off the UI thread and distinguishes committed changes from
failures before commitment. A failed recovery-copy update can leave the previous password on that
copy until the next successful repair.
Automatic recovery of a partially completed wallet password change remains separate work; this
migration does not store old and new account passwords in a recovery journal.

This is application encryption, not forward secrecy or a change to Monero's wallet encryption,
RSA/DSA identities, Tor transport, or mobile notification encryption. Release validation should
include old/new client coexistence, real wallet/profile restore scenarios, and filesystem failure
checks on each supported operating system.
