{pkgs ? import <nixpkgs> {}}:
let
  jdk = pkgs.jdk21.override {enableJavaFX = true;};
  packages = with pkgs; [
    bash
    gnumake
    wget
    git
    procps # the bundled tor launcher lists processes with ps
    zlib # linked by the bundled tor
    jdk
  ];
  profile = "export JAVA_HOME=${jdk.home}"; # gradlew prefers JAVA_HOME over PATH
in
# NixOS lacks the standard ELF loader that Gradle's prebuilt protoc plugin and the bundled tor expect,
# so it enters an FHS environment on startup, which runs interactively and ignores --run.
if builtins.pathExists /etc/NIXOS then
  (pkgs.buildFHSEnv {
    name = "haveno";
    targetPkgs = _: packages;
    inherit profile;
  }).env
else
  pkgs.mkShell {
    buildInputs = packages;
    shellHook = profile;
  }
