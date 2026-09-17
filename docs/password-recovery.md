# Recovering an interrupted password change

Haveno validates the new password and saves a durable account-key backup, updates all wallet and stored connection passwords, then atomically replaces the account key wrapper. Retained trade wallets are updated without restarting their trades. Native handles stay open; RPC wallets briefly close and reopen to flush and copy their locked files, preserving their daemon settings and listeners. Password changes require account initialization and use the existing wallet locks and native or RPC synchronization while updating wallets.

If an unexpected error interrupts the change, Haveno blocks further password changes and password-dependent wallet operations. It does not roll back or retry the change. Keep both passwords, close Haveno, and use the recovery tool. A crash can also leave files using different passwords. Normal startup preserves caches that fail to open, so recovery can still use them.

Quitting during a password change waits for it to finish, subject to the usual four-minute shutdown limit. If that limit forces Haveno to exit before the change completes, keep both passwords and repair the interrupted change before resuming normal use.

## Recovery in the application

On the login screen, choose **Help** in the bottom-left corner, then **Open password repair...**. You can also choose **Repair a password change...** under **Account > Account password**, after an account startup failure, or after a wallet password error during startup.

Haveno closes and opens a separate recovery screen for the same profile. Wait for shutdown to finish, then use **Open directory** to make a complete copy of the application data folder before proceeding. Close any other Haveno or wallet applications using this data.

Enter the **current account password**, repeat it in **Confirm password**, and enter the **previous or attempted password** from the failed change. Leave both current-password fields empty if the account has no password. A mismatch stops recovery before any files are changed. If the account key must be restored from a backup, the confirmed password becomes the password for the recovered account. Unset passwords are always tried automatically, including the internal wallet default `password` and a literal empty password used by accounts created without one before it was normalized; there is no need to type that default. Use **Add another password** if several changes failed with different passwords.

Select **Repair wallets** after confirming the backup. Recovery runs offline, without trading or synchronization. Keep the window open until it finishes. On success, close recovery and start Haveno normally. If it fails, **Try again** opens a fresh recovery screen; re-enter all relevant passwords. If the current account password was rejected, try the other password in the first field. Unknown passwords cannot be recovered or bypassed.

The screen is also available from the desktop launcher with `--recover-password` followed by the network data directory. For example, with an updated macOS installation:

```sh
/Applications/Haveno.app/Contents/MacOS/Haveno --recover-password '/path/to/application-data/xmr_mainnet'
```

This uses the bundled runtime; a separate Java installation or source checkout is unnecessary. Other platforms use their installed Haveno executable with the same arguments. Older installations must first be updated to a version containing this screen. For source builds, use the generated desktop launcher, or `java -cp desktop/build/libs/desktop-1.8.0-SNAPSHOT-all.jar haveno.desktop.app.HavenoAppMain --recover-password '/path/to/application-data/xmr_mainnet'` with the filename produced by your build. Close Haveno before starting recovery manually.

## Terminal recovery

Close Haveno and all its wallet processes, and back up the complete application data directory. From a built Haveno source checkout, run:

```sh
java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword '/path/to/application-data/xmr_mainnet'
```

Build the jar with `./gradlew :daemon:shadowJar` if needed. Use `xmr_local` for local/testnet or `xmr_stagenet` for stagenet. The tool also accepts `xmr_testnet`. The directory must contain `keys` and `wallet`.

Enter the current account password at the hidden prompt and repeat it at the confirmation prompt, leaving both empty if unset. A mismatch stops recovery before any files are changed. Then enter the other password from the interrupted change; finish with an empty entry. If earlier changes used additional passwords, enter those too. Unset passwords are tried automatically. Passwords are never command arguments or written to a recovery file.

If the current account password is rejected, try the other password at the first prompt. A readable account key wrapper is authoritative. Recovery keeps its password and master key unchanged, and repairs wallets and stored connection credentials to match. If the wrapper is missing or no supplied password can open it, recovery can restore the same master key from an automatic backup or abandoned write. The recovered key must authenticate both existing account private-key files before it is installed under the password you entered and confirmed. This becomes the password for the recovered account. Unreadable original wrappers are preserved in the account-key backup folder.

The tool operates offline, including retained trade wallets, orphan wallets and interrupted seed restores. It does not synchronize wallets or start trading. Recovery prefers the installed `monero-wallet-rpc` to isolate failed cache loads, and uses the native library when RPC is unavailable. To specify an RPC executable, append its path as the second argument. Each failed RPC cache probe is discarded before trying the next password from pristine key bytes. RPC requests time out after five minutes; a stalled probe is stopped before continuing. If native recovery exits during a cache load, rerun with the RPC executable so probes run in isolated processes.

A failure stops the tool. After resolving the error, rerun it with all relevant passwords; already repaired wallets can be processed again. Unknown passwords cannot be recovered or bypassed. If no account-key copy can be opened and matched to the account, restore a complete readable backup before recovery.

When wallet keys and cache use different passwords, recovery tries the supplied passwords against copies of the original cache. It preserves the cache's local and multisig state. An existing cache that cannot be opened stops recovery; it is not replaced with an empty cache. Live password changes require a cache for unopened wallets. A missing ordinary wallet cache can be rebuilt from keys, but recovery stops if doing so would cause cleanup to delete an existing cache backup; restore that cache first. Main-wallet rolling backups and complete snapshots with a different or unknown identity remain preserved and do not block rebuilding. Lost cache-only state still requires a readable backup. A missing multisig cache stops recovery until a readable cache backup is restored.

Start Haveno normally after recovery succeeds. Changes involving many retained wallets can take several minutes.

## Backups

If `db/EncryptedConnectionList` was quarantined, restore a readable copy from the same account before recovery. Rolling copies are in `db/backup/backups_EncryptedConnectionList/`. Preserve `db/backup_of_corrupted_data/EncryptedConnectionList` and your complete directory backup until recovery succeeds.

Before rekeying each wallet, Haveno saves a flushed keys/cache pair under `wallet/backup/password-change-<id>/`. After rekeying, it refreshes that pair under the new password. A successful live change retains these new-password copies. Older snapshots of the same main wallet and superseded trade-wallet backups are removed only while primary wallet files remain. Incomplete snapshots and snapshots containing unrecognized files are preserved and reported; keep their previous passwords. A SHA-256 fingerprint of the main-wallet address distinguishes snapshots of different seeds without recording the address itself. Existing plaintext address markers remain readable for cleanup. Offline recovery flushes repaired primary files before cleanup.

Password changes and recovery re-encrypt readable main-wallet rolling keys and caches under the current password after authenticating them to the current wallet. Each cache keeps its historical local state; keys and caches are checked independently because rolling backup timestamps need not match. Successfully protected copies remain available without a retained-backup warning. Copies and snapshots with a different or unknown identity are preserved because restoring another seed can leave a different wallet under the same backup name. Backups of absent wallets are also retained because trades may deliberately keep them after deleting primary files. Password changes and recovery report these unconverted backups as information; keep their previous passwords and restore any missing active wallet from its matching keys/cache pair. The API returns their names in `ChangePasswordReply.retained_wallet_backups`. Backup directory names refer to `wallet/backup`; temporary files are reported with a `wallet/` prefix. The terminal and desktop recovery results list retained names. Unknown entries in the backup directory are also preserved. Reported copies can still be accessible with a previous or unset password.

If the main wallet is missing but backups remain, recovery stops until it is restored. An interrupted seed restore with `haveno_XMR_restore.keys` is repaired in place; normal startup then completes the pending replacement.

Before removing old account-key copies, Haveno flushes and verifies both the current wrapper and a new backup. Abandoned temporary wrappers in the keys directory and account-key backup directory are checked during login and recovery. Successful cleanup retains at most 20 verified current-password copies. Failed logins never create or prune account-key backups. Private-key backups are refreshed only after both account keys have been decrypted successfully. Cleanup removes verified superseded or temporary copies of the same master key. Other account-key backups and temporary wrappers are encrypted under the unchanged account master key in `keys/backup/retained_sym_p12/`. The protected copy is flushed and checked against the original bytes before the original is removed. This also protects truncated wrappers whose key material could still be extracted using a previous or empty password. Login retries unfinished cleanup.

Retained account-key copies follow the protection of the current account password. They require the current master key, so keep the verified current wrapper and its backups. For manual recovery of their exact original bytes, close Haveno and run:

```sh
java -cp daemon/build/libs/daemon-all.jar haveno.core.util.RecoverPassword --export-retained-keys '/path/to/application-data/xmr_mainnet' '/path/to/new-output-directory'
```

Enter the current account password at the hidden prompt. The output directory must not already exist. Exported filenames include a content hash and the original filename to distinguish different copies. Exports may contain unprotected keys; keep them private. The export leaves the account and protected copies unchanged.

Live password changes preserve and report abandoned Haveno temporary wallet writes. Use offline recovery to check these copies before cleanup; they may contain unique local state.

After repaired wallet keys and caches are durable, recovery opens abandoned `<wallet>.keys.new` and Haveno temporary key writes using the supplied passwords, including the internal default. It removes only copies whose primary address matches the repaired wallet. Foreign or unreadable temporary keys from repaired wallets are copied durably into `wallet/backup/recovery-retained-<id>/` before their original paths are removed. This prevents a later Monero password change from overwriting them. Orphan temporary keys remain in place and are reported. A missing main-wallet keys file with a remaining `.keys.new` stops recovery until the authoritative keys and cache are restored. Cache-side `<wallet>.new`, `<wallet>.unportable` and Haveno temporary cache writes are removed when they exactly duplicate the original cache successfully read by recovery. Other temporary caches are preserved in that directory and reported because they may contain unique local state. If recovery was interrupted after replacing the primary cache, a retry can retain an older temporary copy because it no longer matches the cache being recovered. A missing primary cache with an in-place `.new` or `.unportable` must be restored before recovery. An atomic cache write left by an interrupted rebuild is preserved and reported without blocking a retry when no other cache backup needs restoration. Copies already in a retained directory remain reported and are never pruned; their filenames alone do not establish which wallet seed they belong to, so they do not block rebuilding a missing ordinary cache. Restore a matching readable copy to recover its local state. These retained files may still be accessible with a previous or unset password.

Recovery removes its scratch copies after use; leftovers from an interrupted RPC recovery are removed after a successful repair. Copies outside the application directory are unaffected.

If backup cleanup fails after the change, Haveno reports that the password changed successfully and that older backups may remain accessible with a previous or unset password. Use the new password. This warning does not block the account or require password repair; rerunning recovery with the relevant passwords also retries backup cleanup.

Account exports cannot interleave with a password change and are blocked after an unsuccessful change. Close Haveno and copy the complete application data directory to preserve an interrupted state for recovery.
