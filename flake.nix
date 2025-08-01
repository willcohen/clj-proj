{
  description = "Flake to manage clj-proj builds";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixpkgs-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        # Detect actual container architecture for cross-platform builds
        actualSystem = if builtins.pathExists "/etc/os-release" 
          then (if (builtins.match ".*ID=nixos.*" (builtins.readFile "/etc/os-release")) != null
                then (let 
                       # Detect container architecture
                       arch = builtins.readFile (builtins.toPath "/proc/sys/kernel/osrelease");
                       isArm64 = builtins.match ".*aarch64.*" arch != null;
                       isX86_64 = builtins.match ".*x86_64.*" arch != null;
                     in
                       if isArm64 then "aarch64-linux"
                       else if isX86_64 then "x86_64-linux" 
                       else "x86_64-linux")  # fallback
                else system)
          else system;
          
        # Use regular packages for most systems, pkgsStatic for Linux builds
        pkgs = nixpkgs.legacyPackages.${actualSystem};
        
        # For Linux builds in containers, use explicit musl cross-compilation without forcing all static
        buildPkgs = if pkgs.stdenv.isLinux 
          then pkgs.pkgsCross.musl64
          else pkgs;

        # Define common inputs - separate build tools from dev tools
        buildInputs = with buildPkgs; [
          autoconf
          automake
          curl
          gawk
          gnu-config
          libtool
          pkg-config
          sqlite
        ] ++ [
          # Use regular cmake to avoid musl test failures
          pkgs.cmake
        ];
        
        # Development tools that might have GUI dependencies
        devInputs = with pkgs; [
          act
          babashka
          binaryen
          clang
          clj-kondo
          (clojure.override { jdk = graalvm-ce; })
          clojure-lsp  # This likely pulls in dconf
          emscripten
          graalvm-ce
          maven
          podman
          nodejs
          ripgrep
          tcl
        ];

        commonHook = ''
          export JAVA_HOME=${pkgs.jdk24};
          export PATH="${pkgs.jdk24}/bin:$PATH";
          export SQLITE=${buildPkgs.sqlite};
        '';

        # Define cross-compilation package sets (only for Linux)
        crossPkgs =
          if pkgs.stdenv.isLinux then {
            linuxAarch64 = pkgs.pkgsCross.aarch64-multiplatform-musl;
            windowsAmd64 = pkgs.pkgsCross.mingwW64;
            windowsArm64 = pkgs.pkgsCross.aarch64-w64-mingw32;
          } else {};

      in {
        devShells = {
          # The default shell for native development
          default = pkgs.mkShell {
            buildInputs = buildInputs ++ devInputs;
            shellHook = commonHook;
          };
          
          # Minimal build shell for containers (Linux only, uses musl for C tools)
          build = pkgs.mkShell {
            buildInputs = buildInputs ++ [
              # Use glibc versions of JVM tools since they don't link into our output
              pkgs.babashka
              pkgs.clojure
              pkgs.graalvm-ce
              pkgs.maven
            ];
            shellHook = commonHook + ''
              # Use proper musl cross-compiler from pkgsCross.musl64
              export CC=${buildPkgs.buildPackages.gcc}/bin/x86_64-unknown-linux-musl-gcc
              export CXX=${buildPkgs.buildPackages.gcc}/bin/x86_64-unknown-linux-musl-g++
              export AR=${buildPkgs.buildPackages.gcc}/bin/x86_64-unknown-linux-musl-ar
              export RANLIB=${buildPkgs.buildPackages.gcc}/bin/x86_64-unknown-linux-musl-ranlib
              export CFLAGS="-fPIC -D_GNU_SOURCE $CFLAGS"
              export CXXFLAGS="-fPIC -D_GNU_SOURCE $CXXFLAGS"
              export LDFLAGS="-static-libgcc -static-libstdc++ $LDFLAGS"
              export CMAKE_SHARED_LINKER_FLAGS="-static-libgcc -static-libstdc++ -Wl,-Bstatic -lc -lm -lpthread -ldl -Wl,-Bdynamic"
            '';
          };
        } // (pkgs.lib.optionalAttrs pkgs.stdenv.isLinux {
          # A dedicated shell for cross-compiling to Linux ARM64
          linuxAarch64Cross = pkgs.mkShell {
            buildInputs = buildInputs ++ devInputs ++ [
              crossPkgs.linuxAarch64.buildPackages.gcc
            ];
            shellHook = commonHook + ''
              # Use proper aarch64 musl cross-compiler
              export CC=${crossPkgs.linuxAarch64.buildPackages.gcc}/bin/aarch64-unknown-linux-musl-gcc
              export CXX=${crossPkgs.linuxAarch64.buildPackages.gcc}/bin/aarch64-unknown-linux-musl-g++
              export AR=${crossPkgs.linuxAarch64.buildPackages.gcc}/bin/aarch64-unknown-linux-musl-ar
              export RANLIB=${crossPkgs.linuxAarch64.buildPackages.gcc}/bin/aarch64-unknown-linux-musl-ranlib
              export CFLAGS="-fPIC -D_GNU_SOURCE $CFLAGS"
              export CXXFLAGS="-fPIC -D_GNU_SOURCE $CXXFLAGS"
              export LDFLAGS="-static-libgcc -static-libstdc++ $LDFLAGS"
              export CMAKE_SHARED_LINKER_FLAGS="-static-libgcc -static-libstdc++ -Wl,-Bstatic -lc -lm -lpthread -ldl -Wl,-Bdynamic"
            '';
          };
          # A dedicated shell for cross-compiling to Windows AMD64
          windowsAmd64Cross = pkgs.mkShell {
            buildInputs = buildInputs ++ devInputs ++ [
              crossPkgs.windowsAmd64.buildPackages.gcc
              crossPkgs.windowsAmd64.windows.pthreads
            ];
            shellHook = commonHook + ''
              export CC=${crossPkgs.windowsAmd64.buildPackages.gcc}/bin/x86_64-w64-mingw32-gcc
              export CXX=${crossPkgs.windowsAmd64.buildPackages.gcc}/bin/x86_64-w64-mingw32-g++
              export AR=${crossPkgs.windowsAmd64.buildPackages.gcc}/bin/x86_64-w64-mingw32-ar
              export RANLIB=${crossPkgs.windowsAmd64.buildPackages.gcc}/bin/x86_64-w64-mingw32-ranlib
              # Static linking flags for MinGW - avoid mixing -static with -Wl,-Bstatic/-Bdynamic
              export CFLAGS="-static-libgcc -static-libstdc++ $CFLAGS"
              export CXXFLAGS="-static-libgcc -static-libstdc++ $CXXFLAGS"
              export LDFLAGS="-static-libgcc -static-libstdc++ -Wl,--as-needed $LDFLAGS"
            '';
          };
          # A dedicated shell for cross-compiling to Windows ARM64  
          windowsArm64Cross = pkgs.mkShell {
            buildInputs = buildInputs ++ devInputs ++ [
              crossPkgs.windowsArm64.buildPackages.gcc
              crossPkgs.windowsArm64.windows.pthreads
            ];
            shellHook = commonHook + ''
              export CC=${crossPkgs.windowsArm64.buildPackages.gcc}/bin/aarch64-w64-mingw32-gcc
              export CXX=${crossPkgs.windowsArm64.buildPackages.gcc}/bin/aarch64-w64-mingw32-g++
              export AR=${crossPkgs.windowsArm64.buildPackages.gcc}/bin/aarch64-w64-mingw32-ar
              export RANLIB=${crossPkgs.windowsArm64.buildPackages.gcc}/bin/aarch64-w64-mingw32-ranlib
              # Static linking flags for MinGW - avoid mixing -static with -Wl,-Bstatic/-Bdynamic
              export CFLAGS="-static-libgcc -static-libstdc++ $CFLAGS"
              export CXXFLAGS="-static-libgcc -static-libstdc++ $CXXFLAGS"
              export LDFLAGS="-static-libgcc -static-libstdc++ -Wl,--as-needed $LDFLAGS"
            '';
          };
        });
      }
    );
}
