{
  description = "Flake to manage clj-proj builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
    # Per-machine checkout override for clj-native development:
    #   nix develop --override-input clj-native path:/abs/path/to/clj-native
    clj-native.url = "github:willcohen/clj-native";
    clj-native.inputs.nixpkgs.follows = "nixpkgs";
    clj-native.inputs.flake-utils.follows = "flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, clj-native, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        native = clj-native.lib.${system};
        pkgs = nixpkgs.legacyPackages.${native.actualSystem};

        shells = native.mkCrossShells {
          # GraalVM as the JDK makes native-image available to the clojure CLI,
          # and its libgraal lets the :graal PROJ wasm guest JIT-compile.
          # The JDK's libgraal and the org.graalvm.* artifact pins in deps.edn
          # have to move together; jvm_runtime_test guards the pairing.
          jdk = pkgs.graalvmPackages.graalvm-ce;

          # The PROJ build runs sqlite3 to make proj.db.
          extraBuildInputs = [ pkgs.sqlite ];

          # nodejs_26, not the default `nodejs` (the v24 LTS): node 24.x has a
          # libuv regression that aborts the process (uv__io_poll kqueue EBADF)
          # at teardown of a multi-worker pool whose workers did network I/O,
          # for example proj_test's pool after grid fetches. v22 and v26 are
          # clean.
          extraDevInputs = with pkgs; [
            act
            binaryen
            clang
            emscripten
            nodejs_26
            python3
          ];
        };
      in {
        devShells = shells // {
          # The shell for working on this repo, and what .envrc selects. It is
          # `default` plus podman, for `bb test:linux`. CI builds in `default`,
          # without podman and its closure (gtk+3, iptables, libpcap).
          host = shells.default.overrideAttrs (old: {
            buildInputs = old.buildInputs ++ [ pkgs.podman ];
          });
        };
      }
    );
}
