# Release signing keys (bundled trust anchors)

This directory holds the ASCII-armored PGP public keys used to verify installers
downloaded by the in-app updater
(`haveno.desktop.main.overlays.windows.downloadupdate`).

The upstream reference repository ships **no** signing keys, so in-app update
verification is inert and users are directed to download and verify releases
manually.

## Enabling in-app update verification (operational forks)

1. Export your release signer's public key and save it here as
   `<FINGERPRINT>.asc`, where `<FINGERPRINT>` is the full 40 hex character
   primary-key fingerprint (uppercase, no spaces), e.g.
   `1DC3C8C4316A698AC494039CF5B84436F379A1C6.asc`.

2. Add the same fingerprint to `PINNED_SIGNING_KEY_FINGERPRINTS` in
   [`HavenoInstaller.java`](../../java/haveno/desktop/main/overlays/windows/downloadupdate/HavenoInstaller.java).

The bundled key is the trust anchor. `HavenoInstaller.verifySignature` requires
that the key which produced a downloaded signature has a primary-key fingerprint
matching one of the pinned values, so a compromised download host cannot
substitute its own key/signature/installer.

See `docs/deployment-guide.md` for the accompanying hosting steps.
