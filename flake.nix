{
  description = "Flake to manage clj-proj builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};

      in {
        devShell = pkgs.mkShell {
          buildInputs = with pkgs; [
            autoconf
            automake
            binaryen
            clang
            (clojure.override { jdk = jdk17; })
            clojure-lsp
            cmake
            curl
            emscripten
            jdk17
            libtool
            pkg-config
            nodejs
            sqlite

          ];
          shellHook = ''
            export JAVA_HOME=${pkgs.jdk17};
            export PATH="${pkgs.jdk17}/bin:$PATH";
            export SQLITE=${pkgs.sqlite}
            # export TIFF=${pkgs.libtiff}
          '';
        };
        devShells = {
          jdk = self.devShell;
          jdk11 = pkgs.mkShell {
            buildInputs = with pkgs; [
              (clojure.override { jdk = pkgs.jdk11; })
            ];
            shellHook = ''
            export JAVA_HOME=${pkgs.jdk11};
            export PATH="${pkgs.jdk11}/bin:$PATH";
            # export SQLITE=${pkgs.sqlite}
            # export TIFF=${pkgs.libtiff}
          '';
          };
          jdk17 = pkgs.mkShell {
            buildInputs = with pkgs; [
              (clojure.override { jdk = pkgs.jdk17; })
            ];
            shellHook = ''
            export JAVA_HOME=${pkgs.jdk17};
            export PATH="${pkgs.jdk17}/bin:$PATH";
            # export SQLITE=${pkgs.sqlite}
            # export TIFF=${pkgs.libtiff}
          '';
          };
        };
      });
}
