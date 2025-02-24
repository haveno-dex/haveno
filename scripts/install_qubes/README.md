# Haveno on Qubes/Whonix

## **Conventions:**

+ \# – Requires given linux commands to be executed with root privileges either directly as a root user or by use of sudo command

+ $ or % – Requires given linux commands to be executed as a regular non-privileged user

+ \<VAR> – Used to indicate user supplied variable

---

## **Maker vs. Taker Use-Cases:**
+ If user plans to run Haveno for short periods, dynamic onion configuration might fit within their threat model
> For the purpose of this workshop, let’s call this the "taker" use-case

+ If user plans to run Haveno 24/7, using a static onion configuration might be more desirable
> For the purpose of this workshop, let’s call this the "maker" use-case.

<p style="text-align: center;"><em>ONLY Perform the Steps Required for One Use-Case</em></p>

<p style="text-align: center;"><em>OR</em></p>

<p style="text-align: center;"><em>Adjust the Steps to Provide Both</em></p>

	IE:
	Create sys-haveno-taker NetVM & sys-haveno-maker NetVM
	Create haveno-taker AppVM & haveno-maker AppVM
	Adjust NetVM for the for the respective Haveno AppVM as necessary.

---

## **Installation (Scripted & GUI + CLI):**
### *Aquire release files:*
#### In `dispXXXX` AppVM:
##### Clone repository
```shell
% git clone --depth=1 https://github.com/haveno-dex/haveno
```

---

### **Create TemplateVM, NetVM & AppVM:**
#### Scripted
##### In `dispXXXX` AppVM:
###### Prepare files for transfer to `dom0`
```shell
% tar -C haveno/scripts/install_qubes/scripts/0-dom0 -zcvf /tmp/haveno.tgz .
```

##### In `dom0`:
###### Copy files to `dom0`
```shell
$ mkdir -p /tmp/haveno && qvm-run -p dispXXXX 'cat /tmp/haveno.tgz' > /tmp/haveno.tgz && tar -C /tmp/haveno -zxfv /tmp/haveno.tgz
$ bash /tmp/haveno/0.0-dom0.sh && bash /tmp/haveno/0.1-dom0.sh && bash /tmp/haveno/0.2-dom0.sh
```

#### GUI
##### TemplateVM
###### Via `Qubes Manager`:

+ Locate & highlight whonix-workstation-17 (TemplateVM)

+ Right-Click "whonix-workstation-17" and select "Clone qube" from Drop-Down

+ Enter "haveno-template" in "Name"

+ Click OK Button

##### NetVM
###### Via `Qubes Manager`:

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

##### AppVM
###### Via `Qubes Manager`:

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

+ Click "Apply" Button

+ Click "OK" Button


#### CLI
##### TemplateVM
###### In `dom0`:
```shell
$ qvm-clone whonix-workstation-17 haveno-template
```

##### NetVM
##### In `dom0`:
```shell
$ qvm-create --template whonix-gateway-17 --class AppVM --label=orange --property memory=512 --property maxmem=512 --property netvm=sys-firewall sys-haveno && qvm-prefs --set sys-haveno provides_network True
```

#### AppVM
##### In `dom0`:
```shell
$ qvm-create --template haveno-template --class AppVM --label=orange --property memory=2048 --property maxmem=4096 --property netvm=sys-haveno haveno
$ printf 'haveno-Haveno.desktop' | qvm-appmenus --set-whitelist – haveno
```

---

### **Build TemplateVM, NetVM & AppVM:**
#### *TemplateVM - Scripted*
##### In `dispXXXX` AppVM:
```shell
% qvm-copy haveno/scripts/install_qubes/scripts/1-TemplateVM/1.0-haveno-templatevm.sh
```

+ Select "haveno-template" for "Target" of Pop-Up

+ Click OK

##### In `haveno-template` TemplateVM:
```shell
% sudo bash QubesIncoming/dispXXXX/1.0-haveno-templatevm.sh "<PACKAGE_ARCHIVE_URL>" "<PACKAGE_PGP_FINGERPRINT>"
```

<p style="text-align: center;">Example:</p>

```shell
$ sudo bash QubesIncoming/dispXXXX/1.0-haveno-templatevm.sh "https://github.com/nacho/repo/releases/download/v1.0.18/haveno-linux-deb.zip" "ABAF11C65A2970B130ABE3C479BE3E4300411886"
```

#### *TemplateVM - CLI*
##### In `haveno-template` TemplateVM:
###### Download & Import Project PGP Key
```shell
# export https_proxy=http://127.0.0.1:8082
# export KEY_SEARCH="<PACKAGE_PGP_FINGERPRINT>"
# curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$KEY_SEARCH" | gpg --import
```

<p style="text-align: center;">Example:</p>

```shell
# export https_proxy=http://127.0.0.1:8082
# export KEY_SEARCH="ABAF11C65A2970B130ABE3C479BE3E4300411886"
# curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x$KEY_SEARCH" | gpg --import
```

###### Download Release Files
```shell
# export https_proxy=http://127.0.0.1:8082
# curl -sSLo /tmp/hashes.txt https://github.com/nacho/repo/releases/download/v1.0.18/1.0.18-hashes.txt
# curl -sSLo /tmp/hashes.txt.sig https://github.com/nacho/repo/releases/download/v1.0.18/1.0.18-hashes.txt.sig
# curl -sSLo /tmp/haveno.zip https://github.com/nacho/repo/releases/download/v1.0.18/haveno_amd64_deb-latest.zip
# curl -sSLo /tmp/haveno.zip.sig https://github.com/nacho/repo/releases/download/v1.0.18/haveno_amd64_deb-latest.zip.sig
```

<p style="text-align: center;">Note:</p>
<p style="text-align: center;"><em>Above are dummy URLS which MUST be replaced with actual working URLs</em></p>

###### Verify Release Files
```shell
# if [[ $(gpg --digest-algo SHA256 --verify /tmp/hashes.txt.sig /tmp/hashes.txt 2>&1) =~ 'Good signature' ]]; then printf $'SHASUM file has a VALID signature!\n'; else printf $'SHASUMS failed signature check\n' && exit; fi
```

###### Verify Hash, Unpack & Install Package
```shell
# if [[ $(cat /tmp/hashes.txt) =~ $(sha512sum /tmp/haveno*.zip | awk '{ print $1 }') ]] ; then printf $'SHA Hash IS valid!\n' && mkdir -p /usr/share/desktop-directories && cd /tmp && unzip /tmp/haveno*.zip && apt install -y /tmp/haveno*.deb; else printf $'WARNING: Bad Hash!\n' && exit; fi
```

###### Verify Jar
```shell
# if [[ $(cat /tmp/desktop*.SHA-256) =~ $(sha256sum /opt/haveno/lib/app/desktop*.jar | awk '{ print $1 }') ]] ; then printf $'SHA Hash IS valid!\n' && printf 'Happy trading!\n'; else printf $'WARNING: Bad Hash!\n' && exit; fi
```

#### *NetVM - Scripted (Taker)*
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy haveno/scripts/install_qubes/scripts/2-NetVM/2.0-haveno-netvm_taker.sh
```

+ Select "sys-haveno" for "Target" Within Pop-Up

+ Click "OK" Button

##### In `sys-haveno` NetVM:
(Allow bootstrap process to complete)
```shell
% sudo zsh QubesIncoming/dispXXXX/2.0-haveno-netvm_taker.sh
```

#### *NetVM - CLI (Taker)*
##### In `sys-haveno` NetVM:
###### Add `onion-grater` Profile
```shell
# onion-grater-add 40_haveno
```

###### Restart `onion-grater` Service
```shell
# systemctl restart onion-grater.service
# poweroff
```

#### *NetVM - Scripted (Maker)*
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy haveno/scripts/install_qubes/scripts/2-NetVM/2.1-haveno-netvm_maker.sh
```
+ Select "sys-haveno" for "Target" of Pop-Up

+ Click OK

##### In `sys-haveno` NetVM:
```shell
% sudo zsh QubesIncoming/dispXXXX/2.1-haveno-netvm_maker.sh "<HAVENO_APPVM_IP>"
```

<p style="text-align: center;">Example:</p>

```shell
$ sudo zsh QubesIncoming/dispXXXX/2.1-haveno-netvm_maker.sh "1.1.1.1"
```

<p style="text-align: center;">Note:</p>
<p style="text-align: center;">The IPv4 address of the AppVM can easily be found via the Qubes Manger GUI or user's favorite CLI method</p>

#### *NetVM - CLI (Maker)*
##### In `sys-haveno` NetVM:
###### Prepare `haveno-dex` Hidden Service
```shell
# printf "\nConnectionPadding 1\nHiddenServiceDir /var/lib/tor/haveno-dex/\nHiddenServicePort 9999 <HAVENO_APPVM_IP>:9999\n\n" >> /usr/local/etc/torrc.d/50_user.conf
```

###### View & Verify Change
```shell
# tail /usr/local/etc/torrc.d/50_user.conf
```

<p style="text-align: center;"><b>Confirm output contains exactly:</b></p>

>		ConnectionPadding 1
>		HiddenServiceDir /var/lib/tor/haveno-dex/
>		HiddenServicePort 9999 <HAVENO_APPVM_IP>:9999

###### Restart `tor` Service
```shell
# systemctl restart tor@default.service
```

###### Display `haveno-dex` v3 onion address
```shell
# printf "$(</var/lib/tor/haveno-dex/hostname)\n"
```

<p style="text-align: center;">Note:</p>
<p style="text-align: center;">This value is a requirement to build the associated AppVM.</p>

###### Poweroff:
```shell
# poweroff
```

#### *AppVM - Scripted (Taker)*
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy haveno/scripts/install_qubes/scripts/3-AppVM/3.0-haveno-appvm_taker.sh
```

+ Select "haveno" for "Target" of Pop-Up

+ Click OK

##### In `haveno` AppVM:
```shell
% sudo zsh QubesIncoming/dispXXXX/3.0-haveno-appvm_taker.sh
```

#### *AppVM - CLI (Taker)*
##### In `haveno` AppVM:
###### Adjust `sdwdate` Configuration
```shell
# mkdir /usr/local/etc/sdwdate-gui.d
# printf "gateway=sys-haveno\n" > /usr/local/etc/sdwdate-gui.d/50_user.conf
```

###### Prepare Firewall Settings via `/rw/config/rc.local`
```shell
# printf "\n# Prepare Local FW Settings\nmkdir -p /usr/local/etc/whonix_firewall.d\n" >> /rw/config/rc.local
# printf "\n# Poke FW\nprintf \"EXTERNAL_OPEN_PORTS+=\\\\\" 9999 \\\\\"\\\n\" | tee /usr/local/etc/whonix_firewall.d/50_user.conf\n" >> /rw/config/rc.local
# printf "\n# Restart FW\nwhonix_firewall\n\n" >> /rw/config/rc.local
```

###### View & Verify Change
```shell
# tail /rw/config/rc.local
```

<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		# Poke FW
>		printf "EXTERNAL_OPEN_PORTS+=\" 9999 \"\n" | tee /usr/local/etc/whonix_firewall.d/50_user.conf
>		
>		# Restart FW
>		whonix_firewall

###### Restart `whonix_firewall`
```shell
# whonix_firewall
```

###### Create `haveno-Haveno.desktop`
```shell
# mkdir -p /home/$(ls /home)/\.local/share/applications
# sed 's|/opt/haveno/bin/Haveno|/opt/haveno/bin/Haveno --torControlPort=9051 --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on|g' /opt/haveno/lib/haveno-Haveno.desktop > /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
# chown -R $(ls /home):$(ls /home) /home/$(ls /home)/.local/share/applications
```

###### View & Verify Change
```shell
# tail /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
```

<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		[Desktop Entry]
>		Name=Haveno
>		Comment=Haveno
>		Exec=/opt/haveno/bin/Haveno --torControlPort=9051 --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on
>		Icon=/opt/haveno/lib/Haveno.png
>		Terminal=false
>		Type=Application
>		Categories=Network
>		MimeType=

###### Poweroff
```shell
# poweroff
```

#### *AppVM - Scripted (Maker)*
##### In `dispXXXX` AppVM:
```shell
$ qvm-copy haveno/scripts/install_qubes/scripts/3-AppVM/3.1-haveno-appvm_maker.sh
```

+ Select "haveno" for "Target" of Pop-Up

+ Click OK

##### In `haveno`:
```shell
% sudo zsh QubesIncoming/dispXXXX/3.1-haveno-appvm_maker.sh "<HAVENO_NETVM_ONION_ADDRESS>"
```

#### *AppVM - CLI (Maker)*
##### In `haveno` AppVM:
###### Adjust `sdwdate` Configuration
```shell
# mkdir /usr/local/etc/sdwdate-gui.d
# printf "gateway=sys-haveno\n" > /usr/local/etc/sdwdate-gui.d/50_user.conf
```

###### Prepare Firewall Settings via `/rw/config/rc.local`
```shell
# printf "\n# Prepare Local FW Settings\nmkdir -p /usr/local/etc/whonix_firewall.d\n" >> /rw/config/rc.local
# printf "\n# Poke FW\nprintf \"EXTERNAL_OPEN_PORTS+=\\\\\" 9999 \\\\\"\\\n\" | tee /usr/local/etc/whonix_firewall.d/50_user.conf\n" >> /rw/config/rc.local
# printf "\n# Restart FW\nwhonix_firewall\n\n" >> /rw/config/rc.local
```

###### View & Verify Change
```shell
# tail /rw/config/rc.local
```

<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		# Poke FW
>		printf "EXTERNAL_OPEN_PORTS+=\" 9999 \"\n" | tee /usr/local/etc/whonix_firewall.d/50_user.conf
>		
>		# Restart FW
>		whonix_firewall

###### Restart `whonix_firewall`
```shell
# whonix_firewall
```

###### Create `haveno-Haveno.desktop`
```shell
# mkdir -p /home/$(ls /home)/\.local/share/applications
# sed "s|/opt/haveno/bin/Haveno|/opt/haveno/bin/Haveno --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on --nodePort=9999 --hiddenServiceAddress=<HAVENO_NETVM_ONION_ADDRESS>|g" /opt/haveno/lib/haveno-Haveno.desktop > /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
# chown -R $(ls /home):$(ls /home) /home/$(ls /home)/.local/share/applications
```

###### View & Verify Change
```shell
# tail /home/$(ls /home)/.local/share/applications/haveno-Haveno.desktop
```

<p style="text-align: center;"><b>Confirm output contains:</b></p>

>		[Desktop Entry]
>		Name=Haveno
>		Comment=Haveno
>		Exec=/opt/haveno/bin/Haveno --socks5ProxyXmrAddress=127.0.0.1:9050 --useTorForXmr=on --nodePort=9999 --hiddenServiceAddress=<HAVENO_NETVM_ONION_ADDRESS>
>		Icon=/opt/haveno/lib/Haveno.png
>		Terminal=false
>		Type=Application
>		Categories=Network
>		MimeType=

###### Poweroff:
```shell
# poweroff
```

---

### **Remove TemplateVM, NetVM & AppVM:**
#### Scripted
##### In `dom0`:
```shell
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
$ qvm-shutdown --force --quiet haveno haveno-template sys-haveno && qvm-remove --force --quiet haveno haveno-template sys-haveno
```

-------

## *If this helped you, you know what to do*:
### **XMR**:

***85mRPDHW9SuGTDUoMJvt9W4u16Yp1j1SFDrcbfKH2vP1b59nZ62aKVqjfLoyxXrMZYMkNBGzAsuvCCDHPo4AHGx4K8Zmet6***

