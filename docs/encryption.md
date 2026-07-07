# Application encryption

This documents Haveno's application-layer encryption after the v2 upgrade
(issues [#1161](https://github.com/haveno-dex/haveno/issues/1161),
[#2344](https://github.com/haveno-dex/haveno/issues/2344)), the migration
behavior, and the remaining work with step-by-step plans.

## v2 format

All symmetric encryption uses one authenticated format
(`common/src/main/java/haveno/common/crypto/Encryption.java`):

```
"HVN2" (4) || random IV (16) || AES-256-CTR ciphertext || HMAC-SHA256 tag (32)
```

- Encrypt-then-MAC; the tag covers magic || IV || ciphertext.
- Encryption and MAC keys are derived from the master key with HKDF-SHA256
  (infos `haveno.crypto.v2.enc` / `haveno.crypto.v2.mac`), so the master key is
  never used directly by either primitive.
- CTR+HMAC instead of GCM so large stores stream with constant memory in two
  passes (JCE GCM buffers the entire payload on decrypt).
- Decryption auto-detects v2 vs legacy (AES-ECB) blobs by the magic prefix
  (`decryptAuto`, `decryptPayloadWithHmacAuto`). A legacy ECB blob starting
  with the magic has probability 2^-32 and would then fail the tag check.
- The last magic byte is the format version (`Encryption.blobVersion`,
  `CURRENT_BLOB_VERSION`); consumers key their automatic re-encryption to it
  (see "Adding a future format" below).

## Key storage (`sym.key`)

`common/src/main/java/haveno/common/crypto/KeyStorage.java`:

```
"HVNK" (4) || version (1) || kdf id (1) || mem KiB (4) || iterations (4) || parallelism (4) || salt (16) || v2 blob of master key
```

- KEK = Argon2id(password, salt) with parameters stored in the header
  (default 64 MiB / t=3 / p=1; minimal cost when no password is set, since
  hardening adds nothing without a secret). See `PasswordKdf.java`.
- Passwords are UTF-8, NFC-normalized before hashing; only control characters
  are rejected (`CoreAccountService`). Normalization cannot change the
  password of an existing account: legacy `sym.p12` files could only ever be
  created with printable-ASCII passwords (the JDK's PKCS#12 PBE rejects
  anything else at creation, and no alternative provider is registered), and
  NFC normalization is the identity on ASCII.
- A wrong password fails the v2 tag check → `IncorrectPasswordException`.
- The header is unauthenticated, so loads enforce a small file-size cap and
  conservative KDF bounds (256 MiB / 10 iterations / 4 lanes) before allocating.
- `sig.key` / `enc.key` are PKCS#8 keys encrypted as v2 blobs with the master key.

## Migration (all automatic)

| Data | Legacy format | Upgrade trigger |
|---|---|---|
| `sym.p12` | PKCS#12 (fast KDF) | first unlock rewrites `sym.key`, deletes `sym.p12` + its backups |
| `sig.key`/`enc.key` | AES-ECB + HMAC | rewritten on the same unlock |
| Persisted stores | AES-ECB + HMAC stream, or plaintext | rewritten in place at the same unlock (`PlaintextMigration`, covers backups and archives that are never re-persisted); plaintext only accepted while legacy key material exists |
| Append log (`ClosedTrades`) | AES-ECB + HMAC frames | one-time rewrite after first replay |
| XMR connection passwords | AES-ECB | re-encrypted on first read |
| Password change | — | transactional, see "Password changes" below |

All key files are written to a temp file, verified by a read-back and atomically swapped in, so a
failed write can never replace a good file; `sym.key` saves also keep a fresh rolling backup,
written strictly (fsynced and verified byte for byte) so a backup failure surfaces instead of
leaving the live wrapper as the only copy.
Load-time backups are only taken after a successful load so retries against a corrupt file cannot
rotate out good copies.
If key files are lost and a replacement account is generated over the directory, the previous
account's leftover key material (live files, legacy wrapper, transaction artifacts and all
rolling backups) is first moved to a timestamped `keys/lost_account_*` folder, so the
replacement's saves, password changes and backup rotation can never purge what may be the lost
account's only key copies.

Plaintext stores are forgeable (no key needed), so they are parsed only while the account still
holds unmigrated legacy key material — an unforgeable signal, since planting legacy key files
requires the password or master key. Before that material is replaced, every plaintext file in
the persistence tree (live stores, crash-left store temps, and historical copies under
`db/backup/` and `db/backup_of_corrupted_data/`) is encrypted in place (streamed
and atomically swapped, preserving its content), and a durable marker (`db/plaintext_migration`,
see `PlaintextMigration`) is then written, so plaintext is never again accepted. The marker is
written only after every file is encrypted, and migration aborts before key replacement if any
directory in scope cannot be inspected or any file cannot be encrypted; since the legacy key
files are only replaced after migration completes, an interruption at any point simply resumes
at the next unlock. After migration, any plaintext or unauthenticated store file is treated as
corrupt and moved to `backup_of_corrupted_data`, so a forged replacement cannot be laundered
into an authenticated store.

A store's later format-upgrade write (from the legacy encrypted format) does not copy the pre-v2
file into the rolling backups; existing backups of a migrating store are purged and the first
backup is taken from the encrypted replacement.

A failed append-log write (e.g. disk full) is retried from memory and mirrored to
`ClosedTrades.log.pending` (best effort), which is merged and re-appended on the next load, so a
queued batch survives a process kill.

Downgrade to a pre-v2 release is not supported once files are rewritten.

## Password changes

Changing the account password must update the account key wrapper, every Monero wallet, and the
XMR connection credentials together, so it runs as a crash-safe transaction
(`CoreAccountService.changePassword`). Changes are refused until all services are initialized,
so every password-dependent component is represented by a registered listener before the
transaction can commit:

1. An authenticated journal of both passwords (`keys/password_change`, v2-encrypted with the
   master key) and a second wrapper `keys/sym.key.new` under the new password are written
   durably. `sym.key` stays on the old password, so a crash at any point leaves the account
   unlockable with either password (`KeyStorage.loadSecretKey` falls back to the pending wrapper
   while the journal exists).
2. Wallet and credential passwords are changed with idempotent handlers: a wallet already on the
   target password (from an interrupted attempt) passes. Connection credentials persist
   synchronously. All wallets are attempted before failure is reported. Each wallet's rolling
   backups are replaced after its change: the fresh backup is created and verified first, then
   the stale generations are purged with verification, so a failure can never leave the wallet
   without a usable backup (on Windows the wallet is closed for the copy and reopened with the
   change's explicit target password, since open wallet files are locked there and the account
   password has not committed yet); wallet files not represented by trade state are rotated as
   well, and one that opens with neither the old nor the new password fails the change (it must
   be moved out of the wallet directory, never silently deleted). Backups whose wallet cannot be
   rotated — a backup of a wallet that no longer exists, or of a main/trade wallet whose live
   file is missing (e.g. a crashed restore) — still open with the previous password but may be
   the only remaining key copy, so they are never deleted; a warning is logged so the user can
   move them out.
3. On success, `sym.key` is rewrapped under the new password (the single commit point), then
   stale wrappers and backups are purged with verification, a fresh verified backup is taken and
   the journal and pending wrapper are removed (in that order, so they are never cleared while
   the live wrapper is the only copy). A cleanup failure after the commit point keeps the
   journal pending and is retried in the background with capped backoff (and finished on the
   next account open) rather than reporting a failed change whose password already switched.
   On failure before or at the commit point (including a failed `sym.key` rewrite, which leaves
   the old wrapper in place), the handlers converge everything back to the old password.
4. If the process dies mid-change, the next account open recovers deterministically: every
   component is converged to the password the user unlocked with (either one works), then the
   change is committed to it. Recovery waits until services are initialized; a wallet opened
   before then that is still on the counterpart password is healed on open
   (`XmrWalletService.openWallet`), and connection credentials are likewise converged on read
   (`EncryptedConnectionList.getConnections`), so startup can always reach the deferred recovery.
5. Known trade-off: the journal necessarily holds both passwords encrypted under the master
   key, so someone who knows the old password and captures the data directory while a change
   is pending can recover the new password (they can already decrypt all persisted data). The
   window lasts only while a change is in flight or awaiting recovery, and account backups
   (both the API stream and the desktop directory copy share one exclusive guard) are refused
   while one is pending. Avoiding it entirely would require prompting for the
   counterpart password during recovery instead of journaling it. The sharpest case is
   *removing* the password: the pending wrapper is then unlockable without any password, so a
   capture during the window yields the master key and, via the journal, the old password
   itself (which a user may reuse elsewhere). This is inherent to crash recovery having to
   unlock with either password when one of them is empty; the end state of a password removal
   exposes the master key to a capture anyway, the incremental exposure is the old password.

## Accepted limitations (local attacker with write access)

At-rest encryption protects a captured copy of the data directory. An attacker with *write*
access to the live directory (plus, where noted, an old capture) is largely outside the threat
model; these residuals are accepted rather than defended:

- **Master key is not rotated by a password change** (only rewrapped), so an old captured
  `sym.key` plus the retired password can be replayed over the live directory to unlock current
  data. Rotating would require re-encrypting every store and key file at change time.
- **The plaintext-migration gate depends on its marker file**: deleting `db/plaintext_migration`
  *and* replaying captured legacy key files re-opens the one-time plaintext migration. This is
  inherent to supporting restores of pre-v2 account backups, which look identical.
- **v2 blobs are not bound to their file identity**, so two authenticated stores holding the same
  envelope type (e.g. `PendingTrades`/`FailedTrades`) can be swapped undetected.
- **Append-log frames are individually authenticated**; reordering, replaying or truncating whole
  frames of `ClosedTrades.log` is not detected (closed-trade history only).
- **Secrets are not zeroized in memory**: passwords are immutable `String`s and JCE key objects
  copy their bytes, so a heap or core dump of a running (unlocked) process can recover passwords
  and key material. Inherent to the JVM; an attacker who can dump process memory is outside the
  at-rest threat model.

## Adding a future format (v3)

At-rest blobs are versioned by their magic (`"HVN"` + version). If v2 ever needs
replacing:

1. Add `encryptV3`/`decryptV3` (+ stream variants) under magic `HVN3`, teach
   `Encryption.blobVersion` to detect it, and route version 3 in `decryptAuto`,
   `decryptPayloadWithHmacAuto` and `PersistenceManager.readEncrypted`.
2. Switch the writers (the `encryptV2*` call sites) to v3 and bump
   `Encryption.CURRENT_BLOB_VERSION` to 3.
3. Migration is then automatic: every consumer re-encrypts on
   `blobVersion(blob) < CURRENT_BLOB_VERSION` (key files on unlock, stores on
   first read, append-log frames after replay, XMR connection passwords on
   first read), exactly like v1 -> v2. `sym.key` additionally carries its own
   header version and KDF id, so KDF/KEK changes need no new blob format.
4. Network payloads follow the separate two-phase rollout via
   `Version.NETWORK_ENCRYPTION_VERSION` (see below).

## Network rollout (two phases)

Hybrid message seals (`p2p/.../EncryptionService.java`) and trade payment
account payloads (`ProcessSignContractRequest.java`, `Trade.java`) **decrypt**
both formats from this release on, but still **send** legacy, because old
peers cannot read v2 and per-peer capability lookup is unreliable
(`P2PService.findPeersCapabilities`).

To complete the rollout in a later release:

1. Ensure the network has updated to a version ≥ this release (enforce via the
   filter's minimum-version mechanism).
2. Bump `Version.NETWORK_ENCRYPTION_VERSION` to `2` (future formats bump it
   further, following the same two-phase pattern: ship decryption support
   first, raise the sent version once the network has updated). The switch is
   global, not per peer: the filter only stops outdated clients from trading,
   so bump only after in-flight trades with pre-v2 peers have drained (or the
   arbitrator confirms none remain), else their messages become unreadable to
   the outdated side and a funded trade can stall.
3. One release later, legacy sending code can be removed; keep legacy
   *decryption* for mailbox messages until their TTL has passed.

## Remaining work (with plans)

### 1. DSA-2048 message signatures → Ed25519

`Sig.java` uses `SHA256withDSA` (TomP2P legacy). Sound but dated; Ed25519 is
smaller, faster, misuse-resistant. This changes node identity
(`PubKeyRing.signaturePubKeyBytes`), which account signing, dispute
resolution, and mailbox addressing depend on, so it needs its own migration:

1. Add Ed25519 keypair to `KeyRing`/`KeyStorage` (new `ed25519.key`, v2 blob),
   generated on first unlock; add optional field to `PubKeyRing` proto.
2. Sign with both, verify either, keyed by which pubkey the peer advertises.
3. After a filter-enforced minimum version, stop producing DSA signatures.
   Account-age witness data signed with old keys must remain verifiable —
   keep DSA verification code indefinitely.

### 2. RSA-2048 key wrap → X25519 hybrid

`Encryption.encryptSecretKey` (RSA-OAEP-SHA256) is fine today; X25519+HKDF
would be preferable long-term. Same rollout shape as #1 (parallel key in
`PubKeyRing`, sender picks best mutual). Lower priority.

### 3. MobileMessageEncryption (`core/.../notifications/`)

Uses `AES/CBC/NoPadding` with no MAC and a key shared via QR code with the
phone app. Not addressed here because the relay and mobile apps parse the
format. Plan: version the notification envelope, move to the v2 format with
HKDF from the shared key, coordinate a release with the mobile apps, keep
sending v1 to unupgraded app tokens.

### 4. Monero wallet KDF rounds

Wallet files are encrypted by monero with the account password at the default
1 kdf round. `--kdf-rounds N` must match for open and create, and existing
wallet files cannot be reopened with a different value. Plan: pass
`--kdf-rounds` (e.g. 4) in `XmrWalletService`/`XmrConnectionService` wallet-rpc
launch args for *newly created* wallets only, record the value per wallet in
persisted state, and migrate old wallets by `change_wallet_password`-style
rewrite (monero does not support changing kdf rounds in place — requires
re-creating the wallet from keys, so gate it behind an explicit maintenance
step). The account password itself is already Argon2id-hardened for the keys
that encrypt Haveno's stores.

### 5. EncryptedConnectionList scrypt parameters

Passwords there are additionally scrypt-derived (N=32768, r=8, p=6) with only
the salt persisted (`pb.proto` `EncryptedConnectionList.salt`). The whole
store is itself encrypted with the master key, so this is defense-in-depth.
Plan: add optional `n/r/p` fields to the proto, honor them on read (defaults =
current values), write current recommended params, or switch to Argon2id via
a `kdf` field.

### 6. Master key rotation

Changing the account password re-wraps but does not rotate the master
symmetric key, so a leaked master key outlives a password change. Plan: on
password change, generate a new master key, decrypt+re-encrypt `sig.key`,
`enc.key`, and force `persistNow` on all `PersistenceManager` instances and an
append-log `rewrite()`; only then commit the new `sym.key`. Needs a
crash-safe two-phase write (keep old `sym.key` as `sym.key.old` until all
stores are confirmed rewritten).

### 7. At-rest freshness and append-log frame binding

At-rest blobs are authenticated but carry no freshness or identity: an
attacker with filesystem write access can restore an older authenticated copy
of any store, and individual append-log frames are not bound to their log
file, position or predecessor, so captured frames can be reordered, spliced
between `ClosedTrades.log` and its `.pending` queue, or truncated at a frame
boundary without failing authentication. Full rollback cannot be detected
without trusted external state; frame reordering and cross-log splicing could
be, by chaining each frame's MAC over the log identity, a sequence number and
the previous tag. Low priority: the same attacker can simply delete the files,
and the impact is limited to local trade-history state.
