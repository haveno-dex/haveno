# Build environment for Haveno with Nix (NixOS and other systems).
#
# Usage:
#   nix-shell           # enter the build environment
#   make skip-tests     # build as usual
#
# On NixOS the shell runs in an FHS environment because Gradle downloads
# prebuilt native binaries (protoc-gen-grpc-java, ...) that hard-code the
# standard /lib64/ld-linux-x86-64.so.2 loader, which NixOS does not provide.
# Other systems have that loader and use a plain shell. The FHS shell hook
# execs into the sandbox before nix-shell runs a --run or --command
# argument, so on NixOS enter the shell and run commands from it.
#
# Gotcha: if you ever ran the build outside this shell, kill stale
# Gradle daemons first (`./gradlew --stop`). Daemons keep running in
# the environment they were started in and won't see the FHS sandbox.
{ pkgs ? import (fetchTarball {
    # Pinned so all contributors get the same JDK/protobuf toolchain.
    # To bump: change the commit hash at the end of the url, then run
    #   nix-prefetch-url --unpack <url>
    # and paste the resulting hash in the sha256 line.
    #
    # nixos-26.05 branch as of 2026-11-09
    url = "https://github.com/NixOS/nixpkgs/archive/"
        + "c56a87d8e41ce69bd2af57eb35dcc117a9dfca33.tar.gz";
    sha256 = "06g8y8y9bysnpxrl6bfqmn63l8w2lx2yhj0618r34j5jfqy5vjpx";
  }) { } }:

let
  jdk = pkgs.jdk21.override { enableJavaFX = true; };

  buildPkgs = p: [
    p.bash
    p.gnumake
    p.git
    p.wget
    p.zlib
    jdk
  ];

  # gradlew prefers JAVA_HOME over PATH, so point it at the shell's JDK.
  profile = "export JAVA_HOME=${jdk.home}";
in

if builtins.pathExists /etc/NIXOS then
  (pkgs.buildFHSEnv {
    name = "haveno-build";
    targetPkgs = buildPkgs;
    inherit profile;
  }).env
else
  pkgs.mkShell {
    buildInputs = buildPkgs pkgs;
    shellHook = profile;
  }
