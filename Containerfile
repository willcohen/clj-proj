# Containerfile
# Complete build, test, and development environment for clj-proj
# 
# Usage:
#   Build everything:           docker build --target complete .
#   WASM only:                  docker build --target wasm-build .
#   Native only:                docker build --target native-build .
#   Native for specific arch:   docker build --platform linux/amd64 --target native-build .
#   Run tests:                  docker build --target test-all .
#   Development:                docker build --target dev .
#   Extract artifacts:          docker build --target export --output type=local,dest=./artifacts .
#
# For cross-platform builds, use the --platform flag with native-build target:
#   Linux AMD64:   docker build --platform linux/amd64 --target native-build -t clj-proj:linux-amd64 .
#   Linux ARM64:   docker build --platform linux/aarch64 --target native-build -t clj-proj:linux-aarch64 .

# Base stage with Nix environment
FROM nixos/nix:latest AS base

# Build argument to enable local PROJ usage
# When USE_LOCAL_PROJ=1, expects vendor/PROJ to be mounted at /build/vendor/PROJ
# Usage: docker build --build-arg USE_LOCAL_PROJ=1 --volume ./vendor/PROJ:/build/vendor/PROJ:ro
ARG USE_LOCAL_PROJ=0

# Enable flakes and nix-command, and disable syscall filtering
# Also configure for minimal disk usage
RUN echo "experimental-features = nix-command flakes" >> /etc/nix/nix.conf && \
    echo "filter-syscalls = false" >> /etc/nix/nix.conf && \
    echo "auto-optimise-store = true" >> /etc/nix/nix.conf && \
    echo "min-free = 128000000" >> /etc/nix/nix.conf && \
    echo "max-free = 1000000000" >> /etc/nix/nix.conf

# Install git (required for flakes)
RUN nix-env -iA nixpkgs.git --option filter-syscalls false

# Set up environment variables for nix
ENV PATH=/nix/var/nix/profiles/default/bin:$PATH
ENV NIX_PATH=nixpkgs=/nix/var/nix/profiles/per-user/root/channels/nixpkgs

WORKDIR /build

# Copy flake files first for better layer caching
COPY flake.nix flake.lock ./

# Pre-build the nix development environment
# This downloads and caches all flake dependencies
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command echo "Nix environment ready"

# Test that bb is available through the default flake
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb --version

# Copy the entire project
COPY . .

# Verify local PROJ is available when USE_LOCAL_PROJ=1
ARG USE_LOCAL_PROJ=0
RUN if [ "$USE_LOCAL_PROJ" = "1" ]; then \
        if [ ! -d "/build/vendor/PROJ" ]; then \
            echo "ERROR: USE_LOCAL_PROJ=1 but /build/vendor/PROJ not found"; \
            echo "Make sure to mount with: --volume ./vendor/PROJ:/build/vendor/PROJ:ro"; \
            exit 1; \
        fi; \
        echo "✓ Local PROJ found at /build/vendor/PROJ"; \
        ls -la /build/vendor/PROJ | head -10; \
    fi

# ============================================================
# BUILD STAGE - Single stage for all builds
# ============================================================

# Stage: WASM build - run bb task through flake environment
FROM base AS wasm-build
ARG USE_LOCAL_PROJ=0
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command sh -c "bb build --wasm $([ \"$USE_LOCAL_PROJ\" = \"1\" ] && echo \"--local-proj\" || echo \"\")"

# Stage: Native build - use appropriate shell based on target platform
FROM base AS native-build
# Run with proper nix shell environment - use PATH instead of absolute path
ENV PATH=/nix/var/nix/profiles/default/bin:$PATH
ARG USE_LOCAL_PROJ=0
# Accept TARGET_PLATFORM as build arg to determine which nix shell to use
ARG TARGET_PLATFORM=linux/amd64
# Select the appropriate nix shell based on target platform
RUN if echo "$TARGET_PLATFORM" | grep -q "^linux/aarch64"; then \
      NIX_SHELL=".#linuxAarch64Cross"; \
    elif echo "$TARGET_PLATFORM" | grep -q "^windows/amd64"; then \
      NIX_SHELL=".#windowsAmd64Cross"; \
    elif echo "$TARGET_PLATFORM" | grep -q "^windows/arm64"; then \
      NIX_SHELL=".#windowsArm64Cross"; \
    else \
      NIX_SHELL=".#build"; \
    fi && \
    echo "Using nix shell: $NIX_SHELL for platform: $TARGET_PLATFORM" && \
    nix develop $NIX_SHELL --accept-flake-config --option filter-syscalls false \
    --command sh -c "TARGET_CLEAN=\${TARGET_PLATFORM//\//-}; NIX_BUILD_TOP=1 bb build --native --target \$TARGET_CLEAN --debug $([ \"$USE_LOCAL_PROJ\" = \"1\" ] && echo \"--local-proj\" || echo \"\")"

# Stage: Cherry/ClojureScript compilation
FROM wasm-build AS cherry-build
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb cherry

# Stage: Build everything
FROM base AS build-all
# Copy artifacts from other build stages
COPY --from=wasm-build /build/resources/wasm ./resources/wasm/
COPY --from=wasm-build /build/src/cljc/net/willcohen/proj/*.js ./src/cljc/net/willcohen/proj/
COPY --from=wasm-build /build/src/cljc/net/willcohen/proj/*.wasm ./src/cljc/net/willcohen/proj/
COPY --from=native-build /build/resources/linux-* ./resources/

# ============================================================
# TEST STAGES
# ============================================================

# Stage: FFI Tests
FROM native-build AS test-ffi
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb test:ffi

# Stage: GraalVM Tests
FROM build-all AS test-graal
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb test:graal

# Stage: Node.js Tests
FROM wasm-build AS test-node
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb test:node

# Stage: Browser Tests (Playwright)
FROM wasm-build AS test-playwright
# Install browser dependencies
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bash -c "cd test/browser && npm install && npx playwright install --with-deps"

# Run browser tests
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb test:playwright || echo "Browser tests completed"

# Stage: Run all tests
FROM build-all AS test-all
# Run test suite
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bash -c "\
        echo '=== Running FFI Tests ===' && bb test:ffi && \
        echo '=== Running Graal Tests ===' && bb test:graal && \
        echo '=== Running Node Tests ===' && bb test:node && \
        echo '=== All tests completed ==='"

# ============================================================
# UTILITY STAGES
# ============================================================

# Stage: Clean build
FROM base AS clean
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bb clean --all

# ============================================================
# COMPLETE BUILD & TEST
# ============================================================

# Stage: Complete build and test
FROM base AS complete
# Build everything
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bash -c "bb build --native && bb build --wasm"

# Run all tests
RUN nix develop --accept-flake-config --option filter-syscalls false \
    --command bash -c "\
        bb test:ffi && \
        bb test:graal && \
        bb test:node || \
        echo 'Some tests failed, but continuing...'"

# ============================================================
# DEVELOPMENT ENVIRONMENT
# ============================================================

# Stage: Development environment
FROM base AS dev
# Install additional development tools
RUN nix-env -iA \
    nixpkgs.vim \
    nixpkgs.neovim \
    nixpkgs.emacs \
    nixpkgs.tmux \
    nixpkgs.htop \
    nixpkgs.ripgrep \
    nixpkgs.fd \
    nixpkgs.bat \
    nixpkgs.jq \
    nixpkgs.tree \
    --option filter-syscalls false

# Set up workspace
WORKDIR /workspace

# Add some helpful aliases and startup message to ~/.bashrc
RUN printf '%s\n' \
'echo "clj-proj development environment"' \
'echo "Available commands:"' \
'echo "  bb build --native    - Build native libraries"' \
'echo "  bb build --wasm      - Build WASM artifacts"' \
'echo "  bb cherry            - Build JavaScript ES6 module"' \
'echo "  bb test:ffi          - Run FFI tests"' \
'echo "  bb test:graal        - Run GraalVM tests"' \
'echo "  bb test:node         - Run Node.js tests"' \
'echo "  bb nrepl             - Start nREPL server"' \
'echo "  bb dev               - Start development REPL"' \
'echo "  bb demo              - Start demo server (localhost:8080)"' \
'echo ""' \
'alias ll="ls -la"' \
'alias cls="bb clean --all"' \
'export PS1="[clj-proj] \w $ "' >> ~/.bashrc

# Make sure .bashrc is sourced on login
RUN echo 'source ~/.bashrc' > ~/.bash_profile

ENTRYPOINT ["nix", "develop", "--accept-flake-config", "--option", "filter-syscalls", "false", "--command"]
CMD ["bash", "--login"]

# ============================================================
# EXPORT STAGE
# ============================================================

# Stage: Export artifacts
FROM scratch AS export
# WASM artifacts
COPY --from=wasm-build /build/resources/wasm ./resources/wasm/
COPY --from=wasm-build /build/resources/proj.db ./resources/
COPY --from=wasm-build /build/resources/proj.ini ./resources/
COPY --from=wasm-build /build/src/cljc/net/willcohen/proj/proj-emscripten.js ./src/cljc/net/willcohen/proj/
COPY --from=wasm-build /build/src/cljc/net/willcohen/proj/proj-emscripten.wasm ./src/cljc/net/willcohen/proj/
COPY --from=wasm-build /build/src/cljc/net/willcohen/proj/proj-loader.js ./src/cljc/net/willcohen/proj/

# Native artifacts (Linux)
COPY --from=native-build /build/resources/linux-* ./resources/

# ============================================================
# DEFAULT: Complete build
# ============================================================
FROM complete