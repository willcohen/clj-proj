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
        inherit (native) buildPkgs;

        shells = native.mkCrossShells {
          # GraalVM as the JDK makes native-image available to the clojure CLI,
          # and its libgraal lets the :graal PROJ wasm guest JIT-compile.
          # The JDK's libgraal and the org.graalvm.* artifact pins in deps.edn
          # have to move together; jvm_runtime_test guards the pairing.
          jdk = pkgs.graalvmPackages.graalvm-ce;

          # PROJ links against SQLite for its grid database.
          extraBuildInputs = [ buildPkgs.sqlite ];

          # nodejs_26, not the default `nodejs` (the v24 LTS): node 24.x has a
          # libuv regression that aborts the process (uv__io_poll kqueue EBADF)
          # at teardown of a multi-worker pool whose workers did network I/O,
          # for example proj_test's pool after grid fetches. v22 and v26 are
          # clean.
          # No container runtime in this list. The Containerfile base stage
          # runs plain `nix develop`, which resolves to the `default` shell
          # these inputs feed, so anything here is built into every image.
          # podman would drag its closure (gtk+3, iptables, libpcap) in, and
          # nothing inside a container ever starts a container. The `host`
          # shell below adds podman for local use.
          extraDevInputs = with pkgs; [
            act
            binaryen
            clang
            emscripten
            nodejs_26
            python3
          ];

          extraShellHook = ''
            export SQLITE=${buildPkgs.sqlite}
          '';
        };
      in {
        devShells = shells // {
          # The shell for working on this repo, and what .envrc selects. It is
          # `default` plus podman, so `bb build --cross` and `bb test:linux`
          # find a container runtime.
          #
          # podman belongs here and not in `default`, because the Containerfile
          # base stage runs plain `nix develop`. Only `default` reaches the
          # image, so this split keeps the runtime on the host and out of every
          # build. clj-native picks the runtime with
          # (or (fs/which "podman") (fs/which "docker")) and gives no override,
          # so podman on PATH here also decides it over a running Docker.
          host = shells.default.overrideAttrs (old: {
            buildInputs = old.buildInputs ++ [ pkgs.podman ];
          });
        };
      }
    );
}
