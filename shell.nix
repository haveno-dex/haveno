{pkgs ? import <nixpkgs> {}}:
pkgs.mkShell {
  buildInputs = with pkgs; [
    bash
    gnumake
    wget
    git
    javaPackages.openjfx21
    (pkgs.jdk21.override {enableJavaFX = true;})
  ];
}
