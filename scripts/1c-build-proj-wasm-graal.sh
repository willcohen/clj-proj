#!/usr/bin/env bash

VERSION=$1
SQLITE_VERSION=$2
SQLITE_VERSION_URL=$3
LIBTIFF_VERSION=$4

echo "entering graal"

pwd

mkdir -p .emscriptencache
export EM_CACHE=$(pwd)/.emscriptencache

if [ -d "sqlite-autoconf-$SQLITE_VERSION_URL" ]; then
    rm -rf "sqlite-autoconf-$SQLITE_VERSION_URL"
fi

tar xvzf sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz

pushd sqlite-autoconf-$SQLITE_VERSION_URL

mkdir -p sqlite-build-wasm
pushd sqlite-build-wasm

export CFLAGS="-s ERROR_ON_UNDEFINED_SYMBOLS=0 \
               -DSQLITE_OMIT_LOAD_EXTENSION \
               -DSQLITE_DISABLE_LFS \
               -DSQLITE_LONGDOUBLE_TYPE=double \
               -DSQLITE_ENABLE_JSON1 \
               -DSQLITE_ENABLE_NORMALIZE"
if [ ! -f sqlite3.o ]; then
    emconfigure ../configure \
    --disable-shared --disable-editline --disable-readline \
    --enable-threadsafe=no --enable-dynamic-extensions=no \
    --enable-fts5=yes
    emmake make
    emcc -O2 ../sqlite3.c -c -o sqlite3.o -g3
fi

popd
popd

LIBTIFF_MINOR_VERSION=$(($(echo $LIBTIFF_VERSION | awk -F "." '{print $2}')+1))

if [ -d "libtiff-v$LIBTIFF_VERSION" ]; then
    rm -rf "libtiff-v$LIBTIFF_VERSION"
fi

tar xvzf libtiff-v$LIBTIFF_VERSION.tar.gz

pushd libtiff-v$LIBTIFF_VERSION

case "$(uname -sm)" in
    "Linux x86_64")  LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i386")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i486")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i586")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i686")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i786")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Linux i886")    LIBTIFF_LIBNAME=libtiff.a ;;
    "Darwin x86_64") LIBTIFF_LIBNAME=libtiff.a ;;
    "Darwin arm64")  LIBTIFF_LIBNAME=libtiff.a ;;
    "FreeBSD amd64") LIBTIFF_LIBNAME=libtiff.a ;;
    *)               LIBTIFF_LIBNAME="" ;;
esac

if [ ! -f libtiff/.libs/$LIBTIFF_LIBNAME ]; then
    sed -i 's/glibtoolize/libtoolize/g' autogen.sh
    ./autogen.sh
    emconfigure ./configure --host=wasm32
    emmake make -j${nproc}
fi

popd

if [ -d "PROJ-$VERSION" ]; then
    rm -rf "PROJ-$VERSION"
fi

tar xvzf $VERSION.tar.gz
pushd PROJ-$VERSION

mkdir -p proj-build-wasm
pushd proj-build-wasm

mkdir -p .emscriptencache
export EM_CACHE=$(pwd)/.emscriptencache

if [ ! -f lib/libproj.a ]; then
    cp ../../libtiff-v$LIBTIFF_VERSION/libtiff/.libs/$LIBTIFF_LIBNAME .
    emcmake cmake -DBUILD_APPS=OFF -DBUILD_PROJSYNC=OFF -DBUILD_CCT=OFF -DBUILD_CS2CS=OFF \
            -DBUILD_GEOD=OFF -DBUILD_GIE=OFF -DBUILD_PROJ=OFF -DBUILD_PROJINFO=OFF -DBUILD_PROJSYNC=OFF -DENABLE_CURL=OFF \
            -DSQLite3_INCLUDE_DIR=../../sqlite-autoconf-$SQLITE_VERSION_URL \
            -DSQLite3_LIBRARY=../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o \
            -DSQLite3_VERSION=$SQLITE_VERSION \
            -DCMAKE_BUILD_TYPE=Release \
            -DTIFF_INCLUDE_DIR=../../libtiff-v$LIBTIFF_VERSION/libtiff -DTIFF_LIBRARY=$(pwd)/$LIBTIFF_LIBNAME \
            -DBUILD_SHARED_LIBS=OFF \
            -DCMAKE_POLICY_DEFAULT_CMP0135=NEW \
            -DBUILD_TESTING=OFF \
            ..
    cmake --build .
fi

cp data/proj.db .
cp data/proj.ini .

# Web target not currently working, but this wasm file is used elsewhere
#emcc -o pw.js -O2 -fexceptions -fvisibility=default --target=wasm32-unknown-emscripten lib/libproj.a $LIBTIFF_LIBNAME ../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o -s EXPORTED_FUNCTIONS="$(cat ../../../scripts/exported_functions.json)" -s EXPORTED_RUNTIME_METHODS="$(cat ../../../scripts/exported_runtime_methods.json)" -s ALLOW_MEMORY_GROWTH=1 -s DEFAULT_LIBRARY_FUNCS_TO_INCLUDE="\$Browser" -s ENVIRONMENT="web,shell" -s MODULARIZE -s EXPORT_NAME="pw"

## Originally, pn and pi differed only in that pn didn't insert the pw wasm load
## Since pn always now fails on cherry trying to load a nonexistent pw.wasm, perhaps insertion should be the default case.
#emcc -o pn.js -O0 -fexceptions -fvisibility=default --target=wasm32-unknown-emscripten lib/libproj.a $LIBTIFF_LIBNAME ../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o -s EXPORTED_FUNCTIONS="$(cat ../../../scripts/exported_functions.json)" -s EXPORTED_RUNTIME_METHODS="$(cat ../../../scripts/exported_runtime_methods.json)" -s NO_DISABLE_EXCEPTION_CATCHING -s EXCEPTION_DEBUG=1 -s ALLOW_MEMORY_GROWTH=1 -s DEFAULT_LIBRARY_FUNCS_TO_INCLUDE="\$Browser" -s ENVIRONMENT="node" -s EXPORT_NAME="pn" -s WASM_ASYNC_COMPILATION=0 -s SINGLE_FILE=1
#emcc -o proj_emscripten.node.mjs -O2 -fexceptions -fvisibility=default --target=wasm32-unknown-emscripten lib/libproj.a $LIBTIFF_LIBNAME ../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o -s EXPORTED_FUNCTIONS="$(cat ../../../scripts/exported_functions.json)" -s EXPORTED_RUNTIME_METHODS="$(cat ../../../scripts/exported_runtime_methods.json)" -s ALLOW_MEMORY_GROWTH=1 -s DEFAULT_LIBRARY_FUNCS_TO_INCLUDE="\$Browser" -s ENVIRONMENT="node" -s MODULARIZE -s EXPORT_NAME="proj_emscripten" -s WASM_ASYNC_COMPILATION=0 -s SINGLE_FILE=1

#nouse!!###emcc -o pi.js -O0 -fexceptions -fvisibility=default --target=wasm32-unknown-emscripten lib/libproj.a $LIBTIFF_LIBNAME ../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o -s EXPORTED_FUNCTIONS="$(cat ../../../scripts/exported_functions.json)" -s EXPORTED_RUNTIME_METHODS="$(cat ../../../scripts/exported_runtime_methods.json)" -s NO_DISABLE_EXCEPTION_CATCHING -s EXCEPTION_DEBUG=1 -s ALLOW_MEMORY_GROWTH=1 -s DEFAULT_LIBRARY_FUNCS_TO_INCLUDE="\$Browser" -s ENVIRONMENT="node" -s MODULARIZE -s EXPORT_NAME="pi" -s FORCE_FILESYSTEM=1 --pre-js "../../../scripts/insertwasm.js"

emcc -o pgi.js -O0 -fexceptions -fvisibility=default --target=wasm32-unknown-emscripten lib/libproj.a $LIBTIFF_LIBNAME ../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-wasm/sqlite3.o -s EXPORTED_FUNCTIONS="$(cat ../../../scripts/exported_functions.json)" -s EXPORTED_RUNTIME_METHODS="$(cat ../../../scripts/exported_runtime_methods.json)" -s NO_DISABLE_EXCEPTION_CATCHING -s EXCEPTION_DEBUG=1 -s ALLOW_MEMORY_GROWTH=1 -s DEFAULT_LIBRARY_FUNCS_TO_INCLUDE="\$Browser" -s ENVIRONMENT="web,shell" -s MODULARIZE -s EXPORT_NAME="pgi" -s FORCE_FILESYSTEM=1 --pre-js "../../../scripts/insertwasmgraal.js" -g3 -s WASM_ASYNC_COMPILATION=0 -s INITIAL_MEMORY=128MB -s MAXIMUM_MEMORY=256MB

# if [ ! -f ../../../resources/proj.db ]; then
#     cp proj.db ../../../resources/
# fi

# if [ ! -f ../../../resources/proj.ini ]; then
#     cp proj.ini ../../../resources/
# fi

# if [ -f pw.js ]; then
#     mkdir -p ../../../resources/wasm/
#     cp pw.wasm ../../../resources/wasm/
#     mkdir -p ../../../src/js/proj-emscripten/src
#     mkdir -p ../../../src/js/proj-emscripten/dist
#     cp pw.js ../../../src/js/proj-emscripten/src
#     #cp pn.js ../../../src/js/proj-emscripten/src
#     cp proj_emscripten.node.mjs ../../../src/js/proj-emscripten/dist
mkdir -p ../../../resources/wasm
cp pgi.wasm ../../../resources/wasm
cp pgi.js ../../../src/js/proj-emscripten/src
#     cp proj.db ../../../src/js/proj-emscripten/src
#     #cp pw.wasm ../../../src/js/proj-emscripten/src
#     #cp pn.wasm ../../../src/js/proj-emscripten/src
#     cd ../../../../
# fi
