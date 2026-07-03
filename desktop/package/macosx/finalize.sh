#!/bin/bash

cd ../../

version="0.0.1-SNAPSHOT"

target_dir="releases/$version"

# Set HAVENO_GPG_USER as environment var to the email address used for gpg signing. e.g. HAVENO_GPG_USER=releases@example.com
# Set HAVENO_VM_PATH as environment var to the directory where your shared folders for virtual box are residing

vmPath=$HAVENO_VM_PATH
linux64=$vmPath/vm_shared_ubuntu
win64=$vmPath/vm_shared_windows
macos=$vmPath/vm_shared_macosx

deployDir=deploy

rm -r $target_dir

mkdir -p $target_dir

# Release signer public key(s) and signingkey.asc, if present.
# Operational forks: place each signer's <FINGERPRINT>.asc and a signingkey.asc
# (containing the signer's full fingerprint) in desktop/package/ to have them
# copied alongside the release. See docs/deployment-guide.md.
cp "$target_dir/../../package/"*.asc "$target_dir/" 2>/dev/null || true

dmg="Haveno-$version.dmg"
cp "$macos/$dmg" "$target_dir/"

deb="haveno_$version-1_amd64.deb"
deb64="Haveno-64bit-$version.deb"
cp "$linux64/$deb" "$target_dir/$deb64"

rpm="haveno-$version-1.x86_64.rpm"
rpm64="Haveno-64bit-$version.rpm"
cp "$linux64/$rpm" "$target_dir/$rpm64"

exe="Haveno-$version.exe"
exe64="Haveno-64bit-$version.exe"
cp "$win64/$exe" "$target_dir/$exe64"

rpi="jar-lib-for-raspberry-pi-$version.zip"
cp "$deployDir/$rpi" "$target_dir/"

cd "$target_dir"

echo Create signatures
gpg --digest-algo SHA256 --local-user $HAVENO_GPG_USER --output $dmg.asc --detach-sig --armor $dmg
gpg --digest-algo SHA256 --local-user $HAVENO_GPG_USER --output $deb64.asc --detach-sig --armor $deb64
gpg --digest-algo SHA256 --local-user $HAVENO_GPG_USER --output $rpm64.asc --detach-sig --armor $rpm64
gpg --digest-algo SHA256 --local-user $HAVENO_GPG_USER --output $exe64.asc --detach-sig --armor $exe64
gpg --digest-algo SHA256 --local-user $HAVENO_GPG_USER --output $rpi.asc --detach-sig --armor $rpi

echo Verify signatures
gpg --digest-algo SHA256 --verify $dmg{.asc*,}
gpg --digest-algo SHA256 --verify $deb64{.asc*,}
gpg --digest-algo SHA256 --verify $rpm64{.asc*,}
gpg --digest-algo SHA256 --verify $exe64{.asc*,}
gpg --digest-algo SHA256 --verify $rpi{.asc*,}

mkdir $win64/$version
cp -r . $win64/$version

open "."
