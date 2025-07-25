{
  description = "Flake to manage clj-proj builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

        # Define common inputs and hooks to avoid repetition
        commonInputs = with pkgs; [
          autoconf
          act
          automake
          babashka
          binaryen
          clang
          clj-kondo
          (clojure.override { jdk = graalvm-ce; })
          clojure-lsp
          cmake
          curl
          emscripten
          gawk
          graalvm-ce
          gnu-config
          libtool
          maven
          podman
          pkg-config
          nodejs
          ripgrep
          sqlite
          tcl
        ];

        commonHook = ''
          export JAVA_HOME=${pkgs.jdk24};
          export PATH="${pkgs.jdk24}/bin:$PATH";
          export SQLITE=${pkgs.sqlite};
        '';

        # Define cross-compilation package sets (only for Linux)
        crossPkgs =
          if pkgs.stdenv.isLinux then {
            windowsAmd64 = pkgs.pkgsCross.mingwW64;
            windowsArm64 = pkgs.pkgsCross.aarch64-w64-mingw32;
          } else {};

      in {
        devShells = {
          # The default shell for native development
          default = pkgs.mkShell {
            buildInputs = commonInputs;
            shellHook = commonHook;
          };
        } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
          # A dedicated shell for cross-compiling to Windows AMD64
          windowsAmd64Cross = crossPkgs.windowsAmd64.mkShell {
            buildInputs = commonInputs;
            shellHook = commonHook;
          };
          # A dedicated shell for cross-compiling to Windows ARM64
          windowsArm64Cross = crossPkgs.windowsArm64.mkShell {
            buildInputs = commonInputs;
            shellHook = commonHook;
          };
        });
      }
    );
}
