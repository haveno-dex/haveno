<div align="center"> 
  <img src="https://raw.githubusercontent.com/haveno-dex/haveno-meta/721e52919b28b44d12b6e1e5dac57265f1c05cda/logo/haveno_logo_landscape.svg" alt="Haveno logo">

  [![Codacy Badge](https://app.codacy.com/project/badge/Grade/1a4ddf140d634f2ca1fd120a7cff4574)](https://app.codacy.com/gh/haveno-dex/haveno/dashboard?utm_source=github.com)
  ![GitHub Workflow Status](https://img.shields.io/github/workflow/status/haveno-dex/haveno/CI)
  [![GitHub issues with bounty](https://img.shields.io/github/issues-search/haveno-dex/haveno?color=%23fef2c0&label=Issues%20with%20bounties&query=project%3Ahaveno-dex%2F2)](https://github.com/orgs/haveno-dex/projects/2) | 
  [![Twitter Follow](https://img.shields.io/twitter/follow/HavenoDEX?style=social)](https://twitter.com/havenodex)
  [![Matrix rooms](https://img.shields.io/badge/Matrix%20room-%23haveno-blue)](https://matrix.to/#/#space:haveno.network)
</div>

## What is Haveno?

Haveno (pronounced ha‧ve‧no) is a private and decentralized way to exchange Monero for national currencies or other cryptocurrencies. Haveno uses peer-to-peer networking and multi-signature escrow to facilitate trading without a trusted third party custodian. Disputes can be resolved using non-custodial arbitration. Everything is built around Monero and Tor. See the [FAQ on our website](https://haveno.exchange/faq/) for more information.

Haveno is the Esperanto word for "Harbor". The project is stewarded by a core Team formed of 2 people: ErCiccione and Woodser.

Haveno is still work in progress. There are no binaries available at this stage. If you want to test Haveno you have to compile it first (see [docs/installing.md](docs/installing.md)).

## Status of the project

At the moment Haveno is only a Proof of Concept. It's already possible to initiate crypto <-> XMR and fiat <-> XMR trades, but the platform still needs a lot of work before being available for public use.

The project is divided between multiple repositories:

- **[haveno](https://github.com/haveno-dex/haveno)** - This repository. The heart of Haveno.
- **[haveno-ui-poc](https://github.com/haveno-dex/haveno-ui-poc)** - The PoC of the future user interface. Uses gRPC APIs to serve the UI in React.
- **[haveno-design](https://github.com/haveno-dex/haveno-design)** - Repository for design discussions and development.
- **[haveno-meta](https://github.com/haveno-dex/haveno-meta)** - For project-wide discussions and proposals.
- **[haveno-site](https://github.com/haveno-dex/haveno-site)** - The repository of the website.

If you wish to help, take a look at the [issue tracker](https://github.com/haveno-dex/haveno/issues). We run a bounty program to incentivize development. See [Bounties](#bounties)

The PGP keys of the core team members are in `gpg_keys/`.

## Keep in touch and help out!

Haveno is a community-driven project. For it to be successful it's fundamental to have the support and help of the Monero community. We have our own Matrix server. Registrations are not open at the moment, but the rooms are public and can be joined from any matrix client (like Element). We look forward to hearing from you!

- General discussions: **Haveno** (`#haveno:haveno.network`) relayed on Libera (IRC) (`#haveno`)
- Development discussions: **Haveno Development** (`#haveno-dev:haveno.network`) relayed on Libera (IRC) (`#haveno-dev`)
- Design discussions: **Haveno Design** (`#haveno-design:haveno.network`) relayed on Libera (IRC) (`#haveno-design`)

Email: contact@haveno.exchange

## Running a local Haveno test network

See [docs/installing.md](docs/installing.md)

## Contributing to Haveno

We are looking for help building Haveno. There are many [tasks](https://github.com/haveno-dex/haveno/issues) to complete, and many tasks have bounties.

See the [developer guide](docs/developer-guide.md) to get started developing for Haveno. At this stage, we're mostly looking for help building the backend and APIs.

See [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) for our styling guides.

If you are not able to contribute code and want to contribute development resources, [donations](#support) fund development bounties.

## Bounties

To incentivize development and reward contributors we adopt a simple bounty system. Contributors may be awarded bounties after completing a task (resolving an issue). Take a look at the issues eligible for a bounty on the [dedicated Kanban board](https://github.com/orgs/haveno-dex/projects/2) or look for [issues labelled '💰bounty'](https://github.com/haveno-dex/haveno/issues?q=is%3Aissue+is%3Aopen+label%3A%F0%9F%92%B0bounty) in the main `haveno` repository. [Details and conditions for receiving a bounty](docs/bounties.md).

## Sponsors

Would you like to help us build Haveno? Become a sponsor! We will show your logo here. Contact us at contact@haveno.exchange.

<a href="https://getmonero.org"><img src="/media/sponsors/monero-community.png" title="Monero community" alt="Monero community logo" width="100px"></a>
<a href="https://samouraiwallet.com/"><img src="/media/sponsors/samourai.png" title="Samourai wallet" alt="Samourai wallet logo" width="100px"></a>
<a href="https://cakewallet.com/"><img src="/media/sponsors/cake-logo-blue.jpg" title="Cake wallet" alt="Cake wallet logo" width="100px"></a>
<a href="https://twitter.com/DonYakka"><img src="/media/sponsors/donyakka.jpg" title="Don Yakka" alt="Don Yakka logo" width="100px"></a>
<a href="https://twitter.com/mikedogsmd"><img src="/media/sponsors/mikedogsmd.jpg" title="Mike Dogs, MD" alt="Mike Dogs logo" width="100px"></a>

## Support

To bring Haveno to life, we need resources. If you have the possibility, please consider donating to the project:

### Monero

`42sjokkT9FmiWPqVzrWPFE5NCJXwt96bkBozHf4vgLR9hXyJDqKHEHKVscAARuD7in5wV1meEcSTJTanCTDzidTe2cFXS1F`

![Qr code](https://raw.githubusercontent.com/haveno-dex/haveno/master/media/qrhaveno.png)

If you are using a wallet that supports Openalias (like the 'official' CLI and GUI wallets), you can simply put `donations@haveno.network` as the "receiver" address.

### Bitcoin

`bc1q4j5a9hfjxltfvv66gnfaw6478hagzpmjx3zkam`

![Qr code](https://raw.githubusercontent.com/haveno-dex/haveno/master/media/qrbtc.png)