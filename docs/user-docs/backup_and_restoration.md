# Data backup

Backing up your Haveno data is crucial to ensure you can retain control over trades, disputes, payment accounts, and funds in case of issues with your machine (e.g., drive failure) or Haveno itself (e.g., critical bugs).
All Haveno data is stored right on your computer—it's never stored (or even sent) to a central server, so backing it up is your job. We will see data restoration after the backup.

**Contents:**
- 1 Back up the entire Haveno data directory
-- 1.1 While Haveno is running
-- 1.2 While Haveno is closed
-- 1.3 Encrypt your backup
- 2 Back up payment accounts
- 3 Back up your wallet seed
- 4 Export Tor state
- 5 Export trade history
- 6 Export transaction history

### 1. Back up the entire Haveno data directory
The safest, quickest, easiest, and most comprehensive thing you can do to safeguard your data is to back up the whole data directory at regular intervals. See the various elements of the data directory detailed here. In addition, to make restoring payment accounts easier, you may want to back up an export of your payment accounts.

#### 1.1 While Haveno is running
Go to **Account -> Backup** and put in a location for your backup to be exported to (note that this section is titled "Backup wallet" in the software but it actually exports the entire data directory).

#### 1.2 While Haveno is closed
When Haveno is closed, you can simply copy the entire data directory and paste it somewhere safe. Find the location of your data directory by clicking on the Open Directory button (see screenshot above) or see data directory locations here.

#### 1.3 Encrypt your backup
Backup files are not encrypted. If you’ve set a wallet password, your wallet files will be encrypted, but all your other data will not be encrypted.
We recommend encrypting the whole backup folder with something like gpg, 7-Zip, Cryptomator, etc.

### 2. Back up payment accounts

If you just want to back up your payment accounts, export them from **Account -> National Currency Accounts**.
Be advised: exporting your accounts this way only exports metadata. Aging and signing status are not included. To include account aging and signing status, you also need to save the xmr_mainnet/keys/sig.key file from your data directory.
Because of the way restoring payment accounts works, it's best for most users to back up the whole data directory and back up a payment account export.
Export fiat payment accounts here. You can export altcoin accounts from the Altcoin Accounts tab.

### 3. Back up your wallet seed
Please be sure to properly back up your wallet seed.

### 4. Export Tor state
If you want to carry over a particular onion address (and keep your local reputation), you can replace the xmr_mainnet/tor/hiddenservice folder in your data directory with the one from your backup.

### 5. Export trade history
In **Portfolio -> History** you'll find an Export to CSV button to export your trade history.
There's no way to import this data back into Haveno, but it can be useful to have a copy of this data for yourself for record-keeping, analysis, etc.

### 6. Export transaction history
In **Funds -> Transactions** you'll find an Export to CSV button to export your trade history.
There's no way to import this data back into Haveno, but it can be useful to have a copy of this data for yourself for record-keeping, analysis, etc.

# Restore Haveno data
Restoring application data can be useful to bring back payment accounts, onion addresses, and other items from a backup—or to move your Haveno instance to an entirely new machine.
You can restore an entire data directory at once, or just the parts you want.

**Contents:**
- 1 Restore an entire data directory
- 2 Restore payment accounts
-- 2.1 Restore payment account metadata
-- 2.2 Restore payment account aging and signing status
- 3 Restore onion address
- 4 Restore trade history
- 5 Restore wallet

### 1. Restore an entire data directory
First make sure Haveno is closed. Then copy the entire Haveno directory from your backup and paste it in your machine's default data directory location. If there already is a directory called Haveno there, remove it (or rename it) and replace it with your backup.

Data directories work across operating systems, so you can copy a data directory created on a Mac to the appropriate location on Linux or Windows, and it will work. But, please do not run the same data directory on 2 machines at the same time, even if you don't run both instances at the same time, as data may get corrupted in strange ways.

### 2. Restore payment accounts
A payment account export only contains metadata (name, bank information, etc). For fiat accounts, this means that restoring payment accounts is a 2-step process.

### 3. Restore payment account metadata
If you have a payment accounts export file, import it in **Account -> National Currency Accounts**.

If you don't have an export file, but you do have a full backup, you can salvage your payment account metadata from xmr_mainnet/db/UserPayload by running the strings utility on the UserPayload file (e.g., run strings /path/to/backup/xmr_mainnet/db/UserPayload in a terminal window). The command will output a simplified version of the UserPayload file to your terminal. Scroll up a bit and you should see your payment account information.

Use the output to copy and paste the details into new payment accounts in Haveno, paying special attention to make sure each field is copied over with 100% accuracy (including the salt): even a 1 character difference in any field will cause the hash of the payment account to be different, which means aging and signing status will not be restored in the following step.

There are quirks. Here's an example of output from a strings command:

SEPA
Alice Liddell
DE89370400440532013000
DEUTDE5X*
SKzH
salt
@56655c3738ea9dea3b20f482fff048985a2757e57dff206fbd9e8c4f267f7781

From the output above:
Be wary of extra characters at the beginning or end of a line. In the example above, the * character is not part of the BIC "DEUTDE5X".
Salts are alphanumeric, so the @ is not part of the salt "56655c3738ea9dea3b20f482fff048985a2757e57dff206fbd9e8c4f267f7781".

If you're on Windows, or cannot use the strings utility for some other reason, you can just open UserPayload directly in a text editor, but there will be more cruft to sift through since the file isn't meant to be human-readable.

It may be tempting to just replace the entire UserPayload file from a backup, but this is not recommended, as it may contain other data that could result in data corruption in your new instance.
Restore payment account aging and signing status

Once you've restored your payment account metadata, you'll see the accounts in Haveno, but they'll have no aging or signing status. You can get aging and signing status back by replacing xmr_mainnet/keys/sig.key from your backup.
- Make sure you have no active offers, trades or disputes; once you change sig.key, you will become unreachable.
- Close Haveno. Also make sure you've made a backup of your data directory (just in case).
- Replace /path/to/data/directory/xmr_mainnet/keys/sig.key with the sig.key from your backup.

Upon opening Haveno, you should see account aging and signing status restored for your fiat payment accounts. If you don't, double-check:
- You copied account metadata and salt correctly in the previous step.
- The sig.key you copied is the correct one (i.e., the one you were using when your accounts accrued aging and/or got signed)

### 4. Restore onion address
Your onion address determines your local reputation, so depending on your preferences, you may want to reset it or restore it over time.
If you want to restore it:
- Close Haveno. Also make sure you've made a backup of your data directory (just in case).
- Replace the /path/to/data/directory/xmr_mainnet/tor/hiddenservices/ folder with the xmr_mainnet/tor/hiddenservices/ folder from your backup.

When you open Haveno, your onion address will be restored: this means that the peers that have traded with you will see bubbles with trade counts in their offer books for your offers.

### 5. Restore trade history
You may want to keep your trade history despite changing wallets. You can do so by:
- Closing Haveno.
- Replacing the /path/to/data/directory/xmr_mainnet/db/ClosedTrades file on your new directory with the file from backup with the same name.
You will see bubbles with trade counts in the offer books for any any peers you traded with.

### 6. Restore wallet
If starting a new data directory, it's generally best to just send funds from one Haveno instance to another with an on-chain transaction.
