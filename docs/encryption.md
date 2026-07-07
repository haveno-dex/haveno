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
- A wrong password fails the v2 tag check → `IncorrectPasswordException`.
- `sig.key` / `enc.key` are PKCS#8 keys encrypted as v2 blobs with the master key.

## Migration (all automatic)

| Data | Legacy format | Upgrade trigger |
|---|---|---|
| `sym.p12` | PKCS#12 (fast KDF) | first unlock rewrites `sym.key`, deletes `sym.p12` + its backups |
| `sig.key`/`enc.key` | AES-ECB + HMAC | rewritten on the same unlock |
| Persisted stores | AES-ECB + HMAC stream, or plaintext | re-persisted after first read (`PersistenceManager`) |
| Append log (`ClosedTrades`) | AES-ECB + HMAC frames | one-time rewrite after first replay |
| XMR connection passwords | AES-ECB | re-encrypted on first read |
| Password change | — | rewrites `sym.key`, purges stale password-wrapped backups |

`sym.key` is verified by a read-back before it replaces the previous file, and every save keeps a
fresh rolling backup; load-time backups are only taken after a successful unlock so retries against
a corrupt file cannot rotate out good copies.

A failed append-log write (e.g. disk full) is retried from memory and mirrored to
`ClosedTrades.log.pending` (best effort), which is merged and re-appended on the next load, so a
queued batch survives a process kill.

Downgrade to a pre-v2 release is not supported once files are rewritten.

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
   first, raise the sent version once the network has updated).
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

### 7. ASCII password restriction

`KeyStorage.saveSecretKey` still rejects non-ASCII passwords (a PKCS#12-era
restriction; the UI relies on it). Argon2id takes UTF-8 fine. Plan: lift the
check, normalize passwords with NFC before hashing, keep rejecting only
control characters.
