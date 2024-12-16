#!/usr/bin/env bash

VERSION="9.5.1"
WASM_GIT="HEAD"
SQLITE_VERSION="3.46.1"
SQLITE_VERSION_URL="3460100"
SQLITE_YEAR="2024"
ZLIB_VERSION="1.3.1"

case "$(uname -sm)" in
    "Darwin arm64") LIBTIFF_VERSION="4.6.0" ;;
    *) LIBTIFF_VERSION="4.6.0" ;;
esac

NATIVE_COMPILE=true
WASM_COMPILE=true
GRAAL_COMPILE=true
CROSS_COMPILE=false # not working

LIBTIFF_MINOR_VERSION=$(echo $LIBTIFF_VERSION | awk -F "." '{print $2}')

case "$(uname -sm)" in
    "Linux x86_64")  LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i386")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i486")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i586")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i686")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i786")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i886")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Darwin x86_64") LIBTIFF_LIBNAME=libtiff.$LIBTIFF_MINOR_VERSION.dylib ;;
    "Darwin arm64")  LIBTIFF_LIBNAME=libtiff.$LIBTIFF_MINOR_VERSION.dylib ;;
    "FreeBSD amd64") LIBTIFF_LIBNAME=libtiff.a ;;
    # TODO: Detect others
    *)               LIBTIFF_LIBNAME="" ;;
esac

mkdir -p target
pushd target

if [ ! -f "zlib-$ZLIB_VERSION.tar.gz" ]; then
    curl -OL https://zlib.net/zlib-$ZLIB_VERSION.tar.gz
fi

if [ -d "zlib-$ZLIB_VERSION" ]; then
    rm -rf "zlib-$ZLIB_VERSION"
fi

tar xvzf zlib-$ZLIB_VERSION.tar.gz

if [ ! -f "sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz" ]; then
    curl -OL https://sqlite.org/$SQLITE_YEAR/sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz
fi

if [ -d "sqlite-autoconf-$SQLITE_VERSION_URL" ]; then
    rm -rf "sqlite-autoconf-$SQLITE_VERSION_URL"
fi

if [ ! -f "libtiff-v$LIBTIFF_VERSION.tar.gz" ]; then
    curl -OL https://gitlab.com/libtiff/libtiff/-/archive/v$LIBTIFF_VERSION/libtiff-v$LIBTIFF_VERSION.tar.gz
fi

if [ -d "libtiff-v$LIBTIFF_VERSION" ]; then
    rm -rf "libtiff-v$LIBTIFF_VERSION"
fi

if [[ "$WASM_GIT" == "HEAD" ]]; then
    git clone https://github.com/OSGeo/PROJ
    # Until embedded db is released, pin to a fixed commit for predictability
    # https://github.com/OSGeo/PROJ/pull/4265
    pushd "PROJ"
    git checkout 14f5080
    popd
fi

if [ ! -f $VERSION.tar.gz ]; then
    curl -OL https://github.com/OSGeo/PROJ/archive/refs/tags/$VERSION.tar.gz
fi

if [ -d "PROJ-$VERSION" ]; then
    rm -rf "PROJ-$VERSION"
fi

case "$(uname -sm)" in
    "Linux x86_64")  HOST_PLATFORM=linux-x64 ;;
    "Linux i386")    HOST_PLATFORM=linux-x86 ;;
    "Linux i486")    HOST_PLATFORM=linux-x86 ;;
    "Linux i586")    HOST_PLATFORM=linux-x86 ;;
    "Linux i686")    HOST_PLATFORM=linux-x86 ;;
    "Linux i786")    HOST_PLATFORM=linux-x86 ;;
    "Linux i886")    HOST_PLATFORM=linux-x86 ;;
    "Darwin x86_64") HOST_PLATFORM=darwin-x64 ;;
    "Darwin arm64")  HOST_PLATFORM=darwin-arm64 ;;
    "FreeBSD amd64") HOST_PLATFORM=freebsd-x64 ;;
    # TODO: Detect others
    *)               HOST_PLATFORM="" ;;
esac


if $NATIVE_COMPILE; then
    pwd
    bash ../scripts/1a-build-proj-native.sh $VERSION $SQLITE_VERSION $SQLITE_VERSION_URL $LIBTIFF_VERSION
fi

pwd

if $WASM_COMPILE; then
    bash ../scripts/1b-build-proj-wasm.sh $VERSION $WASM_GIT $SQLITE_VERSION $SQLITE_VERSION_URL $LIBTIFF_VERSION
fi

pwd

if $GRAAL_COMPILE; then
    bash ../scripts/1c-build-proj-wasm-graal.sh $VERSION $SQLITE_VERSION $SQLITE_VERSION_URL $LIBTIFF_VERSION
fi

pwd


if ! $CROSS_COMPILE; then
    exit 0
fi

if ! command -v docker; then
    exit 0
fi
#for image in linux-x64, linux-arm64, windows-shared-x64, windows-arm64; do
#
#
case $HOST_PLATFORM in
    "darwin-arm64")  DOCKER_HOST=arm-linux ;;
    "darwin-x64")    DOCKER_HOST=x64-linux ;;
    "linux-x86")     DOCKER_HOST=x86-linux ;;
    "linux-x64")     DOCKER_HOST=x64-linux ;;
        # TODO: Detect others
    *)               DOCKER_HOST="" ;;
esac



for image in "linux-x64" "linux-arm64"; do

    BUILD_ROOT=PROJ-$VERSION/proj-build-$image

    if [ -d $BUILD_ROOT ]; then
        rm -rf $BUILD_ROOT
    fi

    mkdir -p $BUILD_ROOT
    cp $VERSION.tar.gz $BUILD_ROOT
    cp zlib-$ZLIB_VERSION.tar.gz $BUILD_ROOT
    cp sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz $BUILD_ROOT
    cp libtiff-v$LIBTIFF_VERSION.tar.gz $BUILD_ROOT
    pushd $BUILD_ROOT
    tar xvzf $VERSION.tar.gz
    tar xvzf zlib-$ZLIB_VERSION.tar.gz
    tar xvzf sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz
    tar xvzf libtiff-v$LIBTIFF_VERSION.tar.gz
    popd
    cp ../scripts/1c-build-proj-cross.sh .
    docker pull dockcross/$image
    docker run --rm dockcross/$image > $BUILD_ROOT/dockcross
    chmod +x $BUILD_ROOT/dockcross

    $BUILD_ROOT/dockcross bash 1c-build-proj-cross.sh "$BUILD_ROOT" "$image" "$VERSION" "$SQLITE_VERSION" "$SQLITE_VERSION_URL" "$LIBTIFF_VERSION" "$DOCKER_HOST"

    OUTPUT_ROOT=../resources/$image
    mkdir -p $OUTPUT_ROOT
    if [ -f $BUILD_ROOT/lib/libproj.a ]; then cp $BUILD_ROOT/lib/libproj.a $OUTPUT_ROOT ; fi
done
