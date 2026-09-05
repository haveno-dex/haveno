# Application encryption and migration

This implementation replaces the proposal in [PR #2436](https://github.com/haveno-dex/haveno/pull/2436).
It preserves existing master keys, RSA encryption keys, DSA signing keys, account identity,
contracts, and legacy message encoding. It does not support the unpublished experimental
`HVN2`/`HVNK` formats from earlier versions of that PR.

## Authenticated envelope

New local encryption is implemented in `AuthenticatedEncryption`, separately from the explicit
legacy AES-ECB compatibility routines in `Encryption`.

| Field | Bytes | Encoding |
| --- | ---: | --- |
| Format | 8 | `48 56 4e 45 ff 00 00 02` |
| HKDF salt | 32 | Fresh `SecureRandom` bytes for each envelope |
| CTR IV | 16 | Fresh `SecureRandom` bytes for each envelope |
| Ciphertext | Variable | AES-256-CTR, no padding |
| Authentication tag | 32 | HMAC-SHA256 over the complete header and ciphertext |

For each envelope, [HKDF-SHA256, RFC 5869](https://www.rfc-editor.org/rfc/rfc5869)
derives two independent 32-byte keys from the master key and the envelope's salt:

- Encryption info: UTF-8 `haveno/envelope/2/encryption/` followed by the context.
- Authentication info: UTF-8 `haveno/envelope/2/authentication/` followed by the context.

The master key is never used directly as an AES or MAC key in this format. The complete tag is
compared in constant time. An invalid header, version, tag, length, context, or key fails the
read. No authentication failure retries a different cipher. The seven-byte format family is
reserved; an unknown eighth-byte version fails closed. Legacy random ciphertext could collide
with that family with probability 2^-56; a collision fails closed rather than risking a downgrade.

CTR with encrypt-then-MAC permits constant-memory processing of large stores with the existing
JCE providers. Array decryption verifies the MAC before decrypting. File reads verify the entire
file before calling the protobuf parser, rewind the same open descriptor, and verify again over
the ciphertext actually parsed. Results are returned only after the second tag and exact EOF
check succeed. This protects against in-place modification between passes without plaintext
temporary files or buffering the entire ciphertext. Parsing callbacks must not publish results
or perform side effects themselves.

Writes serialize once, in bounded chunks. The final tag is written only after successful
serialization and cipher finalization. VM errors are not translated into successful migration
or plaintext fallback.

### Contexts

| Use | Context |
| --- | --- |
| Private keys | `private-key/sig`, `private-key/enc` |
| Persistence | `store/` + logical store filename |
| Append-log record | `append-log/` + log filename + `/` + zero-based decimal record index |
| Connection password | `connection-password/` + exact connection URL |
| Password journal | `password-change` |
| Master-key wrapper | `key-wrapper/` + standard Base64 of the complete wrapper header |
| Network message | `network-message/` + Base64(SHA256(bound keys)) |
| Payment account | `payment-account/` + trade ID |

Network bound keys are `uint32_be(wrappedKey.length) || wrappedKey ||
uint32_be(senderSignatureKey.length) || senderSignatureKey`. This authenticates both the
RSA-wrapped AES key and the sender identity, in addition to the existing outer signature.

A monolithic store archived as `<name>.legacy-backup` keeps context `store/<name>`. Normal rolling
backups retain their original bytes and context: restore them under the original live filename.
Renaming a different store to that filename does not bypass authentication.

Append-log records also retain a four-byte big-endian ciphertext length outside the envelope.
New records bind their log and position, so swapping, duplicating, or reordering modern records
fails authentication. The per-record ciphertext limit is 256 MiB; oversized records fail without
discarding the file. Torn final frames are copied, read-back verified and fsynced before repair.
A complete unauthenticatable frame stops replay and leaves the entire original file untouched.
Full rewrites verify every record before the atomic replacement. Failed migration rewrites retain
the authenticated legacy history and retry on a later read.

## Master-key password protection

`sym.key` contains a 25-byte header followed by an authenticated envelope of the existing
32-byte master key (145 bytes total):

```
48 56 4e 4b ff 00 00 01 || profile (1 byte) || salt (16 bytes) || envelope
```

Profile 1 uses [Argon2id v1.3, RFC 9106](https://www.rfc-editor.org/rfc/rfc9106), 64 MiB memory,
three iterations, one lane, and a 32-byte output. These costs are fixed by the wrapper version;
untrusted files cannot request arbitrary memory, iteration counts or parallelism. The complete
header, including profile and salt, is authenticated through the envelope context. Profile 0 is
the explicitly passwordless case, using a public zero-valued wrapping key; it provides no
protection against someone who obtains the files. It avoids unnecessary password hashing when
there is no secret password. A supplied nonempty password is rejected for profile 0.

The account API retains printable ASCII passwords of 8–1024 characters, or no password.
Empty and null mean no password. It does not normalize Unicode, trim whitespace, or silently
replace password characters: the wallet and key wrapper must receive exactly the same password.

### Key migration and writes

1. Authenticate the legacy PKCS#12 wrapper and decode both private keys successfully.
2. While that legacy wrapper is still present, convert valid live plaintext protobuf stores in
   the configured database directory. Only complete, recognized delimited envelopes qualify.
   Unknown/corrupt files remain untouched for recovery. Symlinks and unreadable directory listings
   abort migration. This is the only automatic plaintext compatibility path.
3. Upgrade the two private keys independently, retaining the same key material. Mixed legacy/new
   private-key files after an interruption are readable and the migration resumes.
4. Write the Argon2id wrapper last. Keep an independently durable `sym.key.backup` containing the
   current wrapper before retiring `sym.p12` and its local rolling wrapper backups.

Key writes use unique temporary files, owner-only creation on POSIX, fsync, verified read-back,
atomic replacement, and directory fsync where the JDK supports it. Unsupported atomic replacement
is an error; there is no delete-then-rename fallback. The Windows JDK cannot generally fsync a
directory through `FileChannel`, so directory-entry durability there depends on the filesystem.
An I/O failure after a successful rename can report failure even though the target was replaced;
migration and password recovery are designed to resume that state.

Wrong passwords do not migrate keys or rotate backups. A damaged current wrapper never selects
`sym.p12` or an ordinary backup automatically. Incomplete account directories, including ones
containing only backups or crash-left key temps, are never overwritten by new account generation.

Normal encrypted-store reads no longer retry plaintext parsing. A read/authentication failure
stops that load, preserves the original file, and prevents that persistence manager from writing
empty replacement state. Successfully read legacy encrypted stores are upgraded atomically;
a migration-write failure still returns the authenticated data and retains readable storage.

### Connection credentials

`EncryptedConnectionList.encryption_version` distinguishes legacy password/scrypt encryption
(absent/0) from authenticated master-key encryption (2). Unknown versions fail closed. All entries
are decoded, validated, and converted before publishing the replacement map. The URL is bound
to each new encrypted password. The existing salted null-password encoding is preserved.

Modern connection credentials use the stable account master key, so future account password
changes do not need to re-encrypt them. A synchronous, ordered, checked persistence operation
flushes the initial conversion before a password change can retire the old password. During
recovery, a still-legacy connection list uses the old password from the authenticated journal.

## Password-change recovery

The account password also protects Monero wallets. Updating the wrapper and wallets is not a
single filesystem operation. `CoreAccountService` therefore uses a durable, forward-only journal:

1. Require initialized services and registered connection/wallet handlers. Validate the old
   password and the new password before writing anything.
2. Durably write `password-change`, authenticated under the master key, containing the old and
   new passwords. Then write and verify `sym.key.next` under the new password. This order ensures
   that a failed prepare cannot leave an untracked new-password (possibly passwordless) wrapper.
   No wallet mutation happens before this prepare completes.
3. Flush connection credentials first. Converge every live wallet to the new password, including
   retained wallets discovered on disk that are absent from current trade state. Wallet operations
   are serialized under the relevant wallet locks. A wallet already changed by an interrupted
   attempt is accepted only after the backend accepts the target password. Saves and explicit
   filesystem syncs of the wallet keys/cache must succeed before committing the account wrapper.
   Windows wallets are closed for that sync and reopened under the target password. Failures propagate.
4. Replace the primary wrapper and its verified current-password backup. Remove the pending wrapper
   and journal last. The primary wrapper replacement is the password commit point.

After prepare completes and before the primary commit, either password can unlock the same master key through the primary
or pending wrapper, but the pending wrapper is accepted only with a journal authenticated by
that key. If prepare was interrupted before the pending wrapper was written, use the old password
to reopen and resume the journal. After the primary commit the new password is authoritative. On reopening, recovery
always converges forward to the journal's new password. Wallet opens try the journal's previous
password before any existing cache-repair routine, allowing startup to reach the recovery step.
Recovery waits for services and retries while the account is open. A failure retains the journal;
keep both passwords until it finishes. Starting an unrelated password change is refused.

The API and desktop account-copy operation share the password-change exclusion lock. Account
exports are refused while a journal is present, so an automatic backup cannot archive both
passwords mid-transaction. A local filesystem capture or manual copy can still do so: anyone
who can unlock a pending wrapper can decrypt the journal. Removing a password temporarily makes
the journal's old password accessible without a password. This is a consequence of unattended
crash recovery, and is a reason not to reuse account passwords elsewhere.

## Network rollout

This release **sends legacy network encryption** (`Version.NETWORK_ENCRYPTION_VERSION = 1`)
and **reads legacy and new envelopes**. Both hybrid messages and encrypted payment-account payloads
use that boundary. The compatibility tests require byte-identical legacy output. Payment-account
plaintext must still pass the hash check from the signed trade contract before being installed.

Enabling version 2 sends is a separate deployment decision. A minimum-trade-version filter only
blocks new trades; it does not update existing trade partners, dispute participants, notification
recipients, offline peers, or mailbox recipients. Do not enable new sends merely because a
mandatory update was announced or a mailbox TTL elapsed. Operators must account for all remaining
old recipients, including dormant funded trades, before changing the gate. There is no automatic
time-based activation or unauthenticated peer-capability downgrade negotiation here.

Keep legacy decryption for persisted trade payloads and restored histories even after network
sending eventually changes. This release deliberately retains RSA-OAEP-SHA256 and DSA identities;
changing account identity algorithms requires a separate protocol migration.

## Recovery and limits

- Downgrading the application after local migration is unsupported. Preserve an offline pre-upgrade
  account backup if rollback is needed, and restore it as a complete account with its matching keys.
- Historic database backups, archives, exports, and Monero wallet backups are preserved. They may
  contain plaintext, legacy ciphertext or old-password wallets. This migration does not claim to
  erase earlier copies, and does not delete a potentially sole recoverable wallet key. Restore an
  old plaintext store together with the corresponding legacy account backup, not over modern keys.
- Monero's wallet encryption and KDF remain unchanged. Because wallets still use the account
  password, their files and historical copies can provide an alternative password-guessing target;
  Argon2id on `sym.key` alone does not raise the cost of every offline attack on a complete account.
- Password changes rewrap the master key; they do not rotate it. A previously captured wrapper
  and its password can still expose that master key. Full key rotation would require a separate
  transaction over every encrypted store, archive and identity key.
- Authentication does not establish freshness. An attacker with filesystem write access can
  replay complete old stores, delete files, or truncate a log at a valid record boundary. Record
  position binding detects reordered modern records, but does not provide an external monotonic
  counter or detect rollback of a complete account backup.
- Running-process memory, immutable Java password strings, Monero wallet buffers, manual exports,
  mobile notification encryption, and compromised local administrator accounts are outside the
  new envelope's guarantees. No claim of memory zeroization or secure deletion is made.

## Validation

Regression coverage includes an independently generated OpenSSL/HKDF vector; empty and boundary
sizes; array/stream interoperability; every-byte tampering; truncation and trailing data;
wrong keys and contexts; modification between read passes; serialization and read-back failures;
legacy key/identity preservation; interrupted wrapper replacement; pending password recovery;
plaintext rejection after migration; reordered log records; credential conversion; and signed
network messages through protobuf serialization.

`WalletPasswordChangeRpcTest` additionally exercises fresh, unfunded offline wallets with the
packaged `monero-wallet-rpc`, including a partially changed set of wallets and reopen with the
new password. It skips explicitly when the platform binary is unavailable. Automated tests do
not substitute for OS-specific power-loss testing, a funded multi-peer rollout rehearsal, or
independent review of the new wire format before release.
