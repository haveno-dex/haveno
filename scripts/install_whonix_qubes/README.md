# Install Haveno on Qubes/Whonix


After you already have [`Qubes`](https://www.qubes-os.org/downloads) or [`Whonix`](https://www.whonix.org/wiki/Download) installed:

1. Download [scripts](https://github.com/haveno-dex/haveno/tree/master/scripts/install_whonix_qubes/scripts).
2. Move script(s) to their respective destination (`0.*-dom0.sh` -> `dom0`, `1.0-haveno-templatevm.sh` -> `haveno-template`, etc.).
3. Consecutively execute the following commands in their respective destinations.

---

## **Create VMs**
[`Qubes`](https://www.qubes-os.org/downloads)
### **In `dom0`:**

```shell
$ bash 0.0-dom0.sh && bash 0.1-dom0.sh && bash 0.2-dom0.sh
```

[`Whonix`](https://www.whonix.org/wiki/Download) On Anything Other Than [`Qubes`](https://www.qubes-os.org/downloads)

- Clone `Whonix Workstation` To VM Named `haveno-template`
- Clone `Whonix Gateway` To VM Named `sys-haveno`
- Create New Linked VM Clone Based On `haveno-template` Named `haveno`


## **Build TemplateVM**
### *Via Binary Archive*
#### **In `haveno-template` `TemplateVM`:**

```shell
% sudo bash QubesIncoming/dispXXXX/1.0-haveno-templatevm.sh "<PACKAGE_ARCHIVE_URL>" "<PACKAGE_PGP_FINGERPRINT>"
```

<p style="text-align: center;">Example:</p>

```shell
% sudo bash 1.0-haveno-templatevm.sh "https://github.com/havenoexample/haveno-example/releases/download/v1.0.18/haveno-linux-deb.zip" "ABAF11C65A2970B130ABE3C479BE3E4300411886"
```

### *Via Source*
#### **In `dispXXXX` `AppVM`:**
```shell
% bash 1.0-haveno-templatevm.sh "<JDK_PACKAGE_URL>" "<JDK_SHA_HASH>" "<SOURCE_URL>"
```

<p style="text-align: center;">Example:</p>

```shell
% bash 1.0-haveno-templatevm.sh "https://download.bell-sw.com/java/21.0.6+10/bellsoft-jdk21.0.6+10-linux-amd64.deb" "a5e3fd9f5323de5fc188180c91e0caa777863b5b" "https://github.com/haveno-dex/haveno"
```

#### **In `haveno-template` `TemplateVM`:**

```shell
% sudo apt install -y haveno.deb
```

## **Build NetVM**
### **In `sys-haveno` `NetVM`:**

```shell
% sudo zsh 3.0-haveno-appvm.sh
```

## **Build AppVM**
### **In `haveno` `AppVM`:**

```shell
% sudo zsh 3.0-haveno-appvm.sh
```

---

Complete Documentation Can Be Found [Here](https://github.com/haveno-dex/haveno/blob/master/scripts/install_whonix_qubes/INSTALL.md).
