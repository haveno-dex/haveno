# Security upgrade rollout

This release changes removal authorization, alert signatures, and newly signed trade contracts.
Deploy it as a mandatory upgrade. Do not accept legacy removal signatures or omit the multisig
address check when a peer sends an old request: those fallbacks would leave the vulnerabilities
open. Trade protocol 4 prevents clients from taking incompatible offers before contract signing.

## Release sequence

1. Ship encryption phase 1 from [#2573](https://github.com/haveno-dex/haveno/pull/2573) as an optional
   release first, with network encryption sending still at version 1. Include its readers in this
   mandatory release. Follow its pre-upgrade profile backup and rollback instructions.
2. Before upgrading the alert publisher, publish the upgrade notice from a legacy-format client.
   Old and new alert signatures are mutually incompatible; retain the notice's removal credentials.
   Coordinate trades still negotiating contracts, since their peers must agree on the multisig
   address and contract format. Already signed legacy contracts remain valid after upgrade.
3. Upgrade seeds and arbitrators, then publish the filter with `disableTradeBelowVersion` set to
   the chosen mandatory application release version. Assign that release version before packaging;
   the trade protocol number is separate from the application version used by the filter.
   Publish a new-format alert for upgraded clients as well.
4. Activate encryption version 2 sending only after recipients have compatible readers, including
   existing trade/dispute participants and offline mailbox recipients. If that cannot be established
   for this release, keep version 1 sending and activate version 2 in a later optional update under
   the same minimum version. Retain legacy decryption for historical contracts and messages.

`disableTradeBelowVersion` checks a client's own version. It can block settlement and dispute
actions as well as new trading; it does not disconnect old nodes or upgrade offline clients.
A grace period alone is not evidence that old recipients can decrypt version 2 messages.

## Compatibility and offer migration

Mixed versions reject each other's removals. Canceled offers can remain visible until their TTL
expires on the other side, and old nodes remain vulnerable to removal replay until upgraded.
The mandatory release should not be deferred indefinitely while waiting for encryption activation.

Persisted protocol 3 offers are rebuilt as protocol 4 offers and require fresh arbitrator signatures.
The existing migration invalidates their reserve transaction and scheduled funding metadata and
thaws their reserved outputs so funding can be prepared again. Do not change trade fees or security
deposit requirements in the same release: offers that no longer meet those requirements cannot be
re-signed and are canceled. Verify this migration with a real wallet before release, including
deactivated offers and groups of offers sharing funding.

Release validation must cover legacy/new offer filtering, cancellation propagation, both alert
formats, signed legacy trade continuation, interrupted contract negotiation, and delayed mailbox
delivery. Unit tests do not establish readiness of the live network or its offline recipients.
