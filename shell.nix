# Build environment for Haveno with Nix (works on NixOS and other distros).
#
# Usage:
#   nix-shell           # enter the build environment
#   make skip-tests     # build as usual
#
# The FHS wrapper is required because Gradle downloads prebuilt native
# binaries (protoc-gen-grpc-java, ...) that hard-code the standard
# /lib64/ld-linux-x86-64.so.2 loader, which NixOS does not provide.
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

(pkgs.buildFHSEnv {
  name = "haveno-build";

  targetPkgs = p: with p; [
    bash
    gnumake
    git
    wget
    cmake
    pkg-config
    unzip
    zlib
    stdenv.cc.cc.lib
    javaPackages.openjfx21
    (p.jdk21.override { enableJavaFX = true; })
  ];

 runScript = "bash";
}).env
