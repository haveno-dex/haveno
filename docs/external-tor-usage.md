# **Using External `tor` with Haveno**
## [How to Install little-t-`tor` for Your Platform](https://support.torproject.org/little-t-tor/#little-t-tor_install-little-t-tor)

The following `tor` installation instructions are presented here for convenience.

*  **For the most complete, up-to-date & authoritative steps, readers are encouraged to refer to the [Tor Project's Official Homepage](https://www.torproject.org) linked in the header**

* **Notes:**

  For optimum compatibility with Haveno the running `tor` version should match that of the internal Haveno `tor` version

  For best results, use a version of `tor` which supports the [Onion Service Proof of Work](https://onionservices.torproject.org/technology/security/pow)  (`PoW`) mechanism
  * (IE: `GNU` build of `tor`)

---

* **Note Regarding Admin Access:**

  To install `tor` you need root privileges. Below all commands that need to be run as `root` user like `apt` and `dpkg` are prepended with `#`, while commands to be run as user with `$` resembling the standard prompt in a terminal.

### macOS
#### Install a Package Manager
  Two of the most popular package managers for `macOS` are:

  [`Homebrew`](https://brew.sh)

  and

  [`Macports`](https://www.macports.org)

  (You can use the package manager of your choice)

  + Install [`Homebrew`](https://brew.sh)

    Follow the instructions on [brew.sh](https://brew.sh)

  + Install [`Macports`](https://www.macports.org)

    Follow the instructions on [macports.org](https://www.macports.org)

#### Package Installation
##### [`Homebrew`](https://brew.sh)
  ```shell
  # brew update && brew install tor
  ```

##### [`Macports`](https://www.macports.org)
  ```shell
  # port sync && port install tor
  ```

### Debian / Ubuntu
* *Do **not** use the packages in Ubuntu's universe. In the past they have not reliably been updated. That means you could be missing stability and security fixes.*

* Configure the [Official Tor Package Repository](https://deb.torproject.org/torproject.org)

  Enable the [Official Tor Package Repository](https://deb.torproject.org/torproject.org) following these [instructions](https://support.torproject.org/apt/tor-deb-repo/)

#### Package Installation
```shell
# apt update && apt install tor
```

### Fedora
  * Configure the [Official Tor Package Repository](https://rpm.torproject.org/fedora)

    Enable the [Official Tor Package Repository](https://rpm.torproject.org/fedora) by following these [instructions](https://support.torproject.org/rpm/tor-rpm-install)

#### Package Installation
```
# dnf update && dnf install tor
```

### Arch Linux
#### Package Installation
```shell
# pacman -Fy && pacman -Syu tor
```

### Installing `tor` from source
#### Download Latest Release & Dependencies
The latest release of `tor` can be found on the [download](https://www.torproject.org/download/tor) page

* When building from source:

  *First* install `libevent`,`openssl` & `zlib`

  *(Including the -devel packages when applicable)*

#### Install `tor`
```shell
$ tar -xzf tor-<version>.tar.gz; cd tor-<version>
```

* Replace \<version\> with the latest version of `tor`

  > For example, `tor-0.4.8.14`

```shell
$ ./configure && make
```

* Now you can run `tor` (0.4.3.x and Later) locally like this:

```shell
$ ./src/app/tor
```

Or, you can run `make install` (as `root` if necessary) to install it globally into `/usr/local/`

* Now you can run `tor` directly without absolute path like this:

```shell
$ tor
```

### Windows
#### Download
* Download the `Windows Expert Bundle` from the [Official Tor Project's Download page](https://www.torproject.org/download/tor)

#### Extract
* Extract Archive to Disk

#### Open Terminal
* Open PowerShell with Admin Privileges

#### Change to Location of Extracted Archive
* Navigate to `Tor` Directory

#### Package Installation
* v10
```powershell
PS C:\Tor\> tor.exe –-service install
```

* v11
```powershell
PS C:\Tor\> tor.exe –-service install
```

#### Create Service
```powershell
PS C:\Tor\> sc create tor start=auto binPath="<PATH TO>\Tor\tor.exe -nt-service"
```

#### Start Service
```powershell
PS C:\Tor\> sc start tor
```

## Configuring `tor` via `torrc`
#### [I'm supposed to "edit my torrc". What does that mean?](https://support.torproject.org/tbb/tbb-editing-torrc/)
* Per the [Official Tor Project's support page](https://support.torproject.org/tbb/tbb-editing-torrc/):
  * **WARNING:** Do **NOT** follow random advice instructing you to edit your torrc! Doing so can allow an attacker to compromise your security and anonymity through malicious configuration of your torrc.

    **Note:**

    The `torrc` location will ***not*** match those stated in the documentation linked above and will vary across each platform.

#### [Sample `torrc`](https://gitlab.torproject.org/tpo/core/tor/-/blob/HEAD/src/config/torrc.sample.in)
Users are ***strongly*** encouraged to review both the [Official Tor Project's support page](https://support.torproject.org/tbb/tbb-editing-torrc/) as well as the [sample `torrc`](https://gitlab.torproject.org/tpo/core/tor/-/blob/HEAD/src/config/torrc.sample.in) before proceeding.

#### Enable `torControlPort` in `torrc`
In order for Haveno to use the `--torControlPort` option, it must be enabled and accessible. The most common way to do so is to edit the `torrc` fiel with a text editor to ensure that an entry for `ControlPort` followed by port number to listen on is present in the `torrc` file.

#### [Authentication](https://spec.torproject.org/control-spec/implementation-notes.html#authentication)
Per the [Tor Control Protocol - Implementation Notes](https://spec.torproject.org/control-spec/implementation-notes.html):

  * ***"If the control port is open and no authentication operation is enabled, `tor` trusts any local user that connects to the control port. This is generally a poor idea."***

##### `CookieAuthentication`
If the `CookieAuthentication` option is true, `tor` writes a *"magic cookie"* file named `control_auth_cookie` into its data directory (or to another file specified in the `CookieAuthFile` option).

##### Example:
```shell
ControlPort 9051
CookieAuthentication 1
```

##### `HashedControlPassword`
If the `HashedControlPassword` option is set, it must contain the salted hash of a secret password. The salted hash is computed according to the S2K algorithm in `RFC 2440` of `OpenPGP`, and prefixed with the s2k specifier. This is then encoded in hexadecimal, prefixed by the indicator sequence "16:".

* `HashedControlPassword` can be generated like so:
  ```shell
  $ tor --hash-password <password>
  ```

###### Example:
```shell
ControlPort 9051
HashedControlPassword 16:C01147DC5F4DA2346056668DD23522558D0E0C8B5CC88FE72EEBC51967
```

##### Restart `tor`
`tor` must be restarted for changes to `torrc` to be applied.

### \* ***Optional*** \*
#### [Set Up Your Onion Service](https://community.torproject.org/onion-services/setup)

While not a *strict* requirement for use with Haveno, some users may wish to configure an [Onion Service](https://community.torproject.org/onion-services)

  * ***Only Required When Using The Haveno `--hiddenServiceAddress` Option***

Please see the [Official Tor Project's Documentation](https://community.torproject.org/onion-services/setup) for more information about configuration and usage of these services

---

## Haveno's `tor` Aware Options

Haveno is a natively `tor` aware application and offers **many** flexible configuration options for use by privacy conscious users.

While some are mutually exclusive, many are cross-applicable.

Users are encouraged to experiment with options before use to determine which options best fit their personal threat profile.

### Options
#### `--hiddenServiceAddress`
* Function:

  This option configures a *static* Hidden Service Address to listen on  

* Expected Input Format:

  `<String>`

  (`ed25519`)

* Acceptable Values

  `<v3 Onion Address Value>`

* Default value:

  `null`

#### `--socks5ProxyXmrAddress`
* Function:

  A proxy address to be used for `monero` network

* Expected Input Format:

  `<String>`

* Acceptable Values

  `<Host:Port Value>`

* Default value:

  `null`

#### `--torrcFile`
* Function:

  An existing `torrc`-file to be sourced for `tor`

  **Note:**

  `torrc`-entries which are critical to Haveno's flawless operation (`torrc` options line, `torrc` option, ...) **can not** be overwritten

* Expected Input Format:

  `<String>`

* Acceptable Values

  `<Local File Location Value>`

* Default value:

  `null`

#### `--torrcOptions`
* Function:

  A list of `torrc`-entries to amend to Haveno's `torrc`

    **Note:**

    *`torrc`-entries which are critical to Haveno's flawless operation (`torrc` options line, `torrc` option, ...) can **not** be overwritten*

* Expected Input Format:

  `<String>`

* Acceptable Values

  `<^([^\s,]+\s[^,]+,?\s*)+$>`

* Default value:

  `null`

#### `--torControlHost`
+ Function

  The control `hostname` or `IP` of an already running `tor` service to be used by Haveno

* Expected Input Format

  `<String>`

  (`hostname`, `IPv4` or `IPv6`)

* Acceptable Values

  `<TorControl Host Value>`

* Default Value

  `null`

#### `--torControlPort`
+ Function

  The control port of an already running `tor` service to be used by Haveno

* Expected Input Format

  `<Numeric String>`

* Acceptable Values

  `<TorControlPort Value>`

* Default Value

  `-1`

#### `--torControlPassword`
+ Function

  The password for controlling the already running `tor` service

* Expected Input Format

  `<Alpha-Numeric-Special String>`

* Acceptable Values

  `<Passphrase Value>`

* Default Value

  `null`

#### `--torControlCookieFile`
+ Function

  The cookie file for authenticating against the already running `tor` service
  * Used in conjunction with `--torControlUseSafeCookieAuth` option

* Expected Input Format

  `<Alpha-Numeric-Special String>`

* Acceptable Values

  `<Local File Location>`

* Default Value

  `null`

#### `--torControlUseSafeCookieAuth`
+ Function

  Use the `SafeCookie` method when authenticating to the already running `tor` service

* Expected Input Format

  `null`

* Acceptable Values

  `none`

* Default Value

  `off`

#### `--torStreamIsolation`
+ Function

  Use stream isolation for Tor
  * This option is currently considered ***experimental***

* Expected Input Format

  `<Alpha String>`

* Acceptable Values

  `<on|off>`

* Default Value

  `off`

#### `--useTorForXmr`
+ Function

  Configure `tor` for `monero` connections with ***either***:

  * after_sync

    **or**

  * off

    **or**

  * on

* Expected Input Format

  `<Alpha String>`

* Acceptable Values

  `<AFTER_SYNC|OFF|ON>`

* Default Value

  `AFTER_SYNC`

#### `--socks5DiscoverMode`
+ Function

  Specify discovery mode for `monero` nodes

* Expected Input Format

  `<mode[,...]>`

* Acceptable Values

  `ADDR, DNS, ONION, ALL`

  One or more comma separated.

  *(Will be **OR**'d together)*

* Default Value

  `ALL`

---

## Starting Haveno Using Externally Available `tor`
### Dynamic Onion Assignment via `--torControlPort`
```shell
$ /opt/haveno/bin/Haveno --torControlPort='9051' --torControlCookieFile='/var/run/tor/control.authcookie' --torControlUseSafeCookieAuth --useTorForXmr='on' --socks5ProxyXmrAddress='127.0.0.1:9050'
```

### Static Onion Assignment via `--hiddenServiceAddress`
```shell
$ /opt/haveno/bin/Haveno --socks5ProxyXmrAddress='127.0.0.1:9050' --useTorForXmr='on' --hiddenServiceAddress='2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion'
```
