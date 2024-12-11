#!/usr/bin/env bash

set -ex

BUILD_ROOT=$1
image=$2
VERSION=$3
SQLITE_VERSION=$4
SQLITE_VERSION_URL=$5
LIBTIFF_VERSION=$6
DOCKER_HOST=$7

cd $BUILD_ROOT

if [ -d "sqlite-autoconf-$SQLITE_VERSION_URL" ]; then
    rm -rf "sqlite-autoconf-$SQLITE_VERSION_URL"
fi

tar xvzf sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz

pushd sqlite-autoconf-$SQLITE_VERSION_URL
mkdir -p sqlite-build-$image
pushd sqlite-build-$image

case $image in
    "linux-x64")  DOCKER_TARGET=arm-linux ;;
    "linux-arm64")    DOCKER_TARGET=x64-linux ;;
    "windows-shared-x64")     DOCKER_TARGET=x86_64-w64-mingw32 ;;
    "windows-arm64")     DOCKER_TARGET=arm64-w64-mingw32 ;;
        # TODO: Detect others
    *)               DOCKER_HOST="" ;;
esac


../configure --host=$DOCKER_HOST
make
# gcc shell.c sqlite.c

# pwd
# ls -al

popd
popd

if [ -d "libtiff-v$LIBTIFF_VERSION" ]; then
    rm -rf "libtiff-v$LIBTIFF_VERSION"
fi

tar xvzf libtiff-v$LIBTIFF_VERSION.tar.gz


pushd libtiff-v$LIBTIFF_VERSION
./autogen.sh
./configure
make -j${nproc}

popd

pushd PROJ-$VERSION

cmake -DBUILD_APPS=OFF -DBUILD_PROJSYNC=OFF -DBUILD_CCT=OFF -DBUILD_CS2CS=OFF \
      -DBUILD_GEOD=OFF -DBUILD_GIE=OFF -DBUILD_PROJ=OFF -DBUILD_PROJINFO=OFF -DBUILD_PROJSYNC=OFF -DENABLE_CURL=OFF \
      -DSQLITE3_INCLUDE_DIR=../sqlite-autoconf-$SQLITE_VERSION_URL \
      -DSQLITE3_LIBRARY=../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite3.o \
      -DSQLITE3_VERSION=$SQLITE_VERSION -DTIFF_INCLUDE_DIR=../libtiff-v$LIBTIFF_VERSION/libtiff \
      -DTIFF_LIBRARY=$(pwd)/libtiff.a \
      -DBUILD_SHARED_LIBS=OFF ..
cmake --build .
