# Haveno on Qubes/Whonix

## **Conventions:**

+ \# – Requires given linux commands to be executed with root privileges either directly as a root user or by use of sudo command

+ $ or % – Requires given linux commands to be executed as a regular non-privileged user


## **Maker vs. Taker Use-Cases:**
+ If user plans to run Haveno for short periods, dynamic onion configuration might fit within their threat model
> For the purpose of this workshop, let’s call this the "taker" use-case

+ If user plans to run Haveno 24/7, using a static onion configuration might be more desirable
> For the purpose of this workshop, let’s call this the "maker" use-case.

<p style="text-align: center;"><em>ONLY Perform the Steps Required for One Use-Case</em></p>

<p style="text-align: center;"><em>OR</em></p>

<p style="text-align: center;"><em>Adjust the Steps to Provide Both</em></p>

	IE:
	Create haveno-template-dynamic as well as haveno-template-static

                                  &

	Create sys-haveno-dynamic as well as sys-haveno-static and adjust NetVM for the haveno AppVM as necessary.

## **Installation (Scripted + GUI + CLI):**
### *Create & Start Haveno TemplateVM:*
#### Scripted
##### In `dom0`:

```shell
$ mkdir -p /tmp/haveno && qvm-run -p dispXXXX 'cat /tmp/haveno/0.0-dom0.sh' > /tmp/haveno/0.0-dom0.sh
$ bash /tmp/haveno/0.0-dom0.sh
```
#### GUI
##### Via `Qubes Manager`:

+ Locate & highlight whonix-workstation-17 (TemplateVM)

+ Right-Click "whonix-workstation-17" and select "Clone qube" from Drop-Down

+ Enter "haveno-template" in "Name"

+ Click OK Button

+ Highlight "haveno-template" (TemplateVM)

+ Click "Start/Resume" Button

#### CLI
##### In `dom0`:

```shell
$ qvm-clone whonix-workstation-17 haveno-template && qvm-start haveno-template
```

### **Create & Start Haveno NetVM**:
#### Scripted
##### In `dom0`:
```shell
$ mkdir -p /tmp/haveno && qvm-run -p dispXXXX 'cat /tmp/haveno/0.0-dom1.sh' > /tmp/haveno/0.1-dom0.sh
$ bash /tmp/haveno/0.1-dom0.sh
```

#### GUI
##### Via `Qubes Manager`:

+ Click "New qube" Button

+ Enter "sys-haveno" for "Name and label"

+ Click the Button Beside "Name and label" and Select "orange"

+ Select "whonix-gateway-17" from "Template" Drop-Down

+ Select "sys-firewall" from "Networking" Drop-Down

+ Tick "Launch settings after creation" Radio-Box

+ Click OK

+ Click "Advanced" Tab

+ Enter "512" for "Initial memory"

<p style="text-align: center;"><em>(Within reason, can adjust to personal preference)</em></p>

+ Enter "512" for "Max memory"

<p style="text-align: center;"><em>(Within reason, can adjust to personal preference)</em></p>

+ Tick "Provides network" Radio-Box

+ Click "Apply" Button

+ Click "OK" Button

+ Click "Start/Resume" Button

#### CLI
##### In `dom0`:
```shell
$ qvm-create --template whonix-gateway-17 --class AppVM --label=orange --property memory=512 --property maxmem=512 --property netvm=sys-firewall --property provides_network=true sys-haveno
```

### **Create & Start Haveno AppVM**:
#### Scripted
##### In `dom0`:
```shell
$ mkdir -p /tmp/haveno && qvm-run -p dispXXXX 'cat /tmp/haveno/0.2-dom0.sh' > /tmp/haveno/0.2-dom0.sh
$ bash /tmp/haveno/0.2-dom0.sh
```
#### GUI
##### Via `Qubes Manager`:

+ Click "New qube" Button

+ Enter "haveno" for "Name and label"

+ Click the Button Beside "Name and label" and Select "orange"

+ Select "haveno-template" from "Template" Drop-Down

+ Select "sys-haveno" from "Networking" Drop-Down

+ Tick "Launch settings after creation" Radio-Box

+ Click OK

+ Click "Advanced" Tab

+ Enter "2048" for "Initial memory"

<p style="text-align: center;"><em>(Within reason, can adjust to personal preference)</em></p>

+ Enter "4096" for "Max memory"

<p style="text-align: center;"><em>(Within reason, can adjust to personal preference)</em></p>

+ Click "Applications" Tab

+ Click "<<" Button

+ Highlight "Haveno" Under "Available"

+ Click ">" Button

+ Click "Apply" Button

+ Click "OK" Button

+ Click "Start/Resume" Button

#### CLI
##### In `dom0`:
```shell
$ qvm-create --template haveno-template --class AppVM --label=orange --property memory=2048 --property maxmem=4096 --property netvm=sys-haveno haveno
$ printf 'haveno-Haveno.desktop' | qvm-appmenus --set-whitelist – haveno
```

### *Build Haveno TemplateVM:*
#### Scripted
##### In `dispXXXX` AppVM:

```shell
% qvm-copy /tmp/haveno/1.0-haveno-templatevm.sh
```

+ Select "haveno-template" for "Target" of Pop-Up

+ Click OK

##### In `haveno-template` TemplateVM:
```shell
% sudo bash QubesIncoming/dispXXXX/1.0-haveno-templatevm.sh "PACKAGE_ARCHIVE_URL" "PACKAGE_PGP_FINGERPRINT"
```

<p style="text-align: center;">Example:</p>

```shell
$ sudo bash QubesIncoming/dispXXXX/1.0-haveno-templatevm.sh "https://github.com/example/natto/releases/download/v1.0.18/haveno-linux-deb.zip" "FAA24D878B8D36C90120A897CA02DAC12DAE2D0F"
```

#### CLI
##### In `haveno-template` TemplateVM:
###### Install Dependancies:
```shell
# apt install -y wget
```
(While not a strict dependancy, `wget` is used inorder to provide continuity with the Haveno of Tails solution)


###### Download & Import Project PGP Key:
```shell
# export https_proxy=http://127.0.0.1:8082
# export KEY_SEARCH="<PACKAGE_PGP_FINGERPRINT>"
```

<p style="text-align: center;">Example:</p>

```shell
# export KEY_SEARCH="FAA24D878B8D36C90120A897CA02DAC12DAE2D0F"
# curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$KEY_SEARCH" | gpg –import
```

###### Download Release Files:
```shell
# export https_proxy=http://127.0.0.1:8082
# curl -sSLo /tmp/hashes.txt https://github.com/haveno/example/releases/download/v1.0.18/1.0.18-hashes.txt
# curl -sSLo /tmp/hashes.txt.sig https://github.com/haveno/example/releases/download/v1.0.18/1.0.18-hashes.txt.sig
# curl -sSLo /tmp/haveno.zip https://github.com/haveno/example/releases/download/v1.0.18/haveno_amd64_deb-latest.zip
# curl -sSLo /tmp/haveno.zip.sig https://github.com/haveno/example/releases/download/v1.0.18/haveno_amd64_deb-latest.zip.sig
```

Note:
<p style="text-align: center;"><em>Above are dummy URLS which MUST be replaced with actual working URLs</em></p>

###### Verify Release Files:
```shell
# if [[ $(gpg --digest-algo SHA256 --verify /tmp/hashes.txt.sig /tmp/hashes.txt 2>&1) =~ 'Good signature' ]]; then printf $'SHASUM file has a VALID signature!\n'; else printf $'SHASUMS failed signature check\n' && exit; fi
```

###### Verify Hash, Unpack & Install Package:
```shell
# if [[ $(cat /tmp/hashes.txt) =~ $(sha512sum /tmp/haveno*.zip | awk '{ print $1 }') ]] ; then printf $'SHA Hash IS valid!\n' && mkdir -p /usr/share/desktop-directories && cd /tmp && unzip /tmp/haveno*.zip && apt install -y /tmp/haveno*.deb && chown -R $(ls /home):$(ls /home) /opt/haveno; else printf $'WARNING: Bad Hash!\n' && exit; fi
```


###### Verify Jar:
```shell
# if [[ $(cat /tmp/desktop*.SHA-256) =~ $(sha256sum /opt/haveno/lib/app/desktop*.jar | awk '{ print $1 }') ]] ; then printf $'SHA Hash IS valid!\n' && printf 'Happy trading!\n'; else printf $'WARNING: Bad Hash!\n' && exit; fi
```

### **Build Haveno NetVM:**
#### Scripted (Taker)
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy 2.0-haveno-netvm_taker.sh
```

+ Select "sys-haveno" for "Target" Within Pop-Up

+ Click "OK" Button

##### In `sys-haveno` NetVM:
```shell
% sudo zsh QubesIncoming/dev/2.0-haveno-netvm_taker.sh
```

#### CLI (Taker)
##### In `sys-haveno` NetVM:
###### *Add Taker onion-grater Profile*:
```shell
# onion-grater-add 40_haveno
# poweroff
```

#### Scripted (Maker)
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy 2.1-haveno-netvm_maker.sh
```
+ Select "sys-haveno" for "Target" of Pop-Up

+ Click OK

##### In `sys-haveno` NetVM:
```shell
% sudo zsh QubesIncoming/dispXXXX/2.1-haveno-netvm_maker.sh "<HAVENO_APPVM_IP>"
```

<p style="text-align: center;">Note:</p>
<p style="text-align: center;"><em>The IPv4 address of the haveno AppVM can be found via the Qubes Manager GUI</em></p>

<p style="text-align: center;">Example:</p>

```shell
$ sudo zsh QubesIncoming/dispXXXX/2.1-haveno-netvm_maker.sh "10.111.0.42"
```

#### CLI (Maker)
##### In `sys-haveno` NetVM:
###### Prepare Maker Hidden Service:
```shell
# printf "\nConnectionPadding 1\nHiddenServiceDir /var/lib/tor/haveno-dex/\nHiddenServicePort 9999 <HAVENO_APPVM_IP>:9999\n\n" >> /usr/local/etc/torrc.d/50_user.conf
```

###### View & Verify Change:
```shell
# tail /usr/local/etc/torrc.d/50_user.conf
```

<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		ConnectionPadding 1
>		HiddenServiceDir /var/lib/tor/haveno-dex/
>		HiddenServicePort 9999 <HAVENO_APPVM_IP>:9999




### **Build Haveno AppVM**:
#### Scripted (Taker)
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy /tmp/haveno/3.0-haveno-appvm_taker.sh
```

+ Select "haveno" for "Target" of Pop-Up

+ Click OK

##### In `haveno` AppVM:
```shell
% sudo zsh QubesIncoming/dispXXXX/3.0-haveno-appvm_taker.sh
```
#### CLI (Taker)
##### In `haveno` AppVM:
###### Prepare Firewall Settings
```shell
# printf "\n# Prepare Local FW Settings\nmkdir -p /usr/local/etc/whonix_firewall.d\n" >> /rw/config/rc.local
# printf "\n# Poke FW\nprintf \"EXTERNAL_OPEN_PORTS+=\\\\\" 9999 \\\\\"\\\n\" | tee /usr/local/etc/whonix_firewall.d/50_user.conf\n" >> /rw/config/rc.local
# printf "\n# Restart FW\nwhonix_firewall\n\n" >> /rw/config/rc.local
```
###### View & Verify Change:
<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		# Poke FW
>		printf "EXTERNAL_OPEN_PORTS+=" 9999 "\n" | tee /usr/local/etc/whonix_firewall.d/50_user.conf
>
>		# Restart FW
>		whonix_firewall

```shell
# tail /rw/config/rc.local
# poweroff
```
#### Scripted (Maker)
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy /tmp/haveno/3.1-haveno-appvm_maker.sh
```

+ Select "haveno" for "Target" of Pop-Up

+ Click OK

##### In `haveno` AppVM:
```shell
% sudo zsh QubesIncoming/dispXXXX/3.0-haveno-appvm_maker.sh "<HAVENO_NETVM_ONION_ADDRESS>"
```
#### CLI (Maker)
##### In `haveno` AppVM:
###### Prepare Maker Hidden Service
```shell
# printf "\nConnectionPadding 1\nHiddenServiceDir /var/lib/tor/haveno-dex/\nHiddenServicePort 9999 $HAVENO_APPVM_IP:9999\n\n" >> /usr/local/etc/torrc.d/50_user.conf
```

###### View & Verify Change
<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		## Haveno-DEX
>		ConnectionPadding 1
>		HiddenServiceDir /var/lib/tor/haveno-dex/
>		HiddenServicePort 9999 <HAVENO_APPVM_IP>:9999

```shell
# tail /usr/local/etc/torrc.d/50_user.conf
# poweroff
```

### **Remove Haveno AppVM, TemplateVM & NetVM:**
#### Scripted
##### In `dom0`:
```shell
$ mkdir -p /tmp/haveno && qvm-run -p dispXXXX 'cat /tmp/haveno/0.3-dom0.sh' > /tmp/haveno/0.3-dom0.sh
$ bash /tmp/haveno/0.3-dom0.sh
```
#### GUI
##### Via `Qubes Manager`:

+ Highlight "haveno" (AppVM)

+ Click "Delete qube"

+ Enter "haveno"

+ Click "OK" Button

+ Highlight "haveno-template" (TemplateVM)

+ Click "Delete qube"

+ Enter "haveno-template"

+ Click "OK" Button

+ Highlight "sys-haveno" (NetVM)

+ Click "Delete qube"

+ Enter "sys-haveno"

+ Click "OK" Button

#### CLI
##### In `dom0`:
```shell
$ qvm-shutdown --force haveno haveno-template sys-haveno && qvm-remove haveno haveno-template sys-haveno
```


## *If this helped you, you know what to do*:
### **XMR**:

***85mRPDHW9SuGTDUoMJvt9W4u16Yp1j1SFDrcbfKH2vP1b59nZ62aKVqjfLoyxXrMZYMkNBGzAsuvCCDHPo4AHGx4K8Zmet6***
