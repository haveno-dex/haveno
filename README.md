<div align="center">
  <img src="https://raw.githubusercontent.com/haveno-dex/haveno-meta/721e52919b28b44d12b6e1e5dac57265f1c05cda/logo/haveno_logo_landscape.svg" alt="Haveno logo">

  ![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/haveno-dex/haveno/build.yml?branch=master)
  [![GitHub issues with bounty](https://img.shields.io/github/issues-search/haveno-dex/haveno?color=%23fef2c0&label=Issues%20with%20bounties&query=is%3Aopen+is%3Aissue+label%3A%F0%9F%92%B0bounty)](https://github.com/haveno-dex/haveno/issues?q=is%3Aopen+is%3Aissue+label%3A%F0%9F%92%B0bounty)
  [![Twitter Follow](https://img.shields.io/twitter/follow/HavenoDEX?style=social)](https://twitter.com/havenodex)
  [![Matrix rooms](https://img.shields.io/badge/Matrix%20room-%23haveno-blue)](https://matrix.to/#/#haveno:monero.social) [![Contributor Covenant](https://img.shields.io/badge/Contributor%20Covenant-2.1-4baaaa.svg)](https://github.com/haveno-dex/.github/blob/master/CODE_OF_CONDUCT.md)
</div>

## What is Haveno?

Haveno (pronounced ha‧ve‧no) is an open source platform to exchange [Monero](https://getmonero.org) for fiat currencies like USD, EUR, and GBP or other cryptocurrencies like BTC, ETH, and BCH.

Main features:

- Communications are routed through **Tor**, to preserve your privacy.

- Trades are **peer-to-peer**: trades on Haveno happen between people only, there is no central authority.

- Trades are **non-custodial**: Haveno supports arbitration in case something goes wrong during the trade, but arbitrators never have access to your funds.

- There is **No token**, because it's not needed. Transactions between traders are secured by non-custodial multisignature transactions on the Monero network.

See the [FAQ on our website](https://haveno.exchange/faq/) for more information.

## Haveno Demo

https://github.com/user-attachments/assets/eb6b3af0-78ce-46a7-bfa1-2aacd8649d47

## Installing Haveno

Haveno can be installed on Linux, macOS, and Windows by using a third party installer and network.

> [!note]
> The official Haveno repository does not support making real trades directly.
> 
> To make real trades with Haveno, first find a third party network, and then use their installer or build their repository. We do not endorse any networks at this time.

A test network is also available for users to make test trades using Monero's stagenet. See the [instructions](https://github.com/haveno-dex/haveno/blob/master/docs/installing.md) to build Haveno and connect to the test network.

Alternatively, you can [create your own mainnet network](https://github.com/haveno-dex/haveno/blob/master/docs/create-mainnet.md).

Note that Haveno is being actively developed. If you find issues or bugs, please let us know.

## Main repositories

- **[haveno](https://github.com/haveno-dex/haveno)** - This repository. The core of Haveno.
- **[haveno-ts](https://github.com/haveno-dex/haveno-ts)** - TypeScript library for using Haveno.
- **[haveno-ui](https://github.com/haveno-dex/haveno-ui)** - A new user interface (WIP).
- **[haveno-meta](https://github.com/haveno-dex/haveno-meta)** - For project-wide discussions and proposals.

If you wish to help, take a look at the repositories above and look for open issues. We run a bounty program to incentivize development. See [Bounties](#bounties).

## Keep in touch and help out!

Haveno is a community-driven project. For it to be successful it's fundamental to have the support and help of the community. Join the community rooms on our Matrix server:

- General discussions: **Haveno** ([#haveno:monero.social](https://matrix.to/#/#haveno:monero.social)) relayed on IRC/Libera (`#haveno`)
- Development discussions: **Haveno Development** ([#haveno-development:monero.social](https://matrix.to/#/#haveno-development:monero.social)) relayed on IRC/Libera (`#haveno-development`)

Email: contact@haveno.exchange
Website: [haveno.exchange](https://haveno.exchange)

## Contributing to Haveno

See the [developer guide](docs/developer-guide.md) to get started developing for Haveno.

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for our styling guides.

If you are not able to contribute code and want to contribute development resources, [donations](#support-and-sponsorships) fund development bounties.

## Bounties

To incentivize development and reward contributors, we adopt a simple bounty system. Contributors may be awarded bounties after completing a task (resolving an issue). Take a look at the [issues labeled '💰bounty'](https://github.com/haveno-dex/haveno/issues?q=is%3Aopen+is%3Aissue+label%3A%F0%9F%92%B0bounty) in the main `haveno` repository. [Details and conditions for receiving a bounty](docs/bounties.md).

## Support and sponsorships

To bring Haveno to life, we need resources. If you have the possibility, please consider [becoming a sponsor](https://haveno.exchange/sponsors/) or donating to the project:

<p>
  <img src="https://raw.githubusercontent.com/haveno-dex/haveno/master/media/donate_monero.png" alt="Donate Monero" width="115" height="115"><br>
  <code>47fo8N5m2VVW4uojadGQVJ34LFR9yXwDrZDRugjvVSjcTWV2WFSoc1XfNpHmxwmVtfNY9wMBch6259G6BXXFmhU49YG1zfB</code>
</p>

If you are using a wallet that supports OpenAlias (like the 'official' CLI and GUI wallets), you can simply put `fund@haveno.exchange` as the "receiver" address.
