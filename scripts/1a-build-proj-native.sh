#!/usr/bin/env bash


VERSION=$1
SQLITE_VERSION=$2
SQLITE_VERSION_URL=$3
LIBTIFF_VERSION=$4

LIBTIFF_MINOR_VERSION=$(echo $LIBTIFF_VERSION | awk -F "." '{print $2}')

case "$(uname -sm)" in
    "Linux x86_64")  LIBRARY_DIR=linux-x64 ;;
    "Linux i386")    LIBRARY_DIR=linux-x86 ;;
    "Linux i486")    LIBRARY_DIR=linux-x86 ;;
    "Linux i586")    LIBRARY_DIR=linux-x86 ;;
    "Linux i686")    LIBRARY_DIR=linux-x86 ;;
    "Linux i786")    LIBRARY_DIR=linux-x86 ;;
    "Linux i886")    LIBRARY_DIR=linux-x86 ;;
    "Darwin x86_64") LIBRARY_DIR=darwin-x64 ;;
    "Darwin arm64")  LIBRARY_DIR=darwin-aarch64 ;;
    "FreeBSD amd64") LIBRARY_DIR=freebsd-x64 ;;
    # TODO: Detect others
    *)               LIBRARY_DIR="" ;;
esac

if [ -d "sqlite-autoconf-$SQLITE_VERSION_URL" ]; then
    rm -rf "sqlite-autoconf-$SQLITE_VERSION_URL"
fi

tar xvzf sqlite-autoconf-$SQLITE_VERSION_URL.tar.gz

pushd sqlite-autoconf-$SQLITE_VERSION_URL

mkdir -p sqlite-build-$LIBRARY_DIR
pushd sqlite-build-$LIBRARY_DIR

../configure
make install

popd
popd

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
    "Darwin x86_64") LIBTIFF_LIBNAME=libtiff.$LIBTIFF_MINOR_VERSION.dylib ;;
    "Darwin arm64")  LIBTIFF_LIBNAME=libtiff.$LIBTIFF_MINOR_VERSION.dylib ;;
    "FreeBSD amd64") LIBTIFF_LIBNAME=libtiff.a ;;
    *)               LIBTIFF_LIBNAME="" ;;
esac

if [ ! -f libtiff/.libs/$LIBTIFF_LIBNAME ]; then
    sed -i 's/glibtoolize/libtoolize/g' autogen.sh
    ./autogen.sh
    #mkdir -p .config
    ./configure
    make -j${nproc}
fi

popd

if [ -d "PROJ-$VERSION" ]; then
    rm -rf "PROJ-$VERSION"
fi

tar xvzf $VERSION.tar.gz

pushd PROJ-$VERSION

mkdir -p proj-build-$LIBRARY_DIR
pushd proj-build-$LIBRARY_DIR

echo $LIBTIFF_LIBNAME

if [ -f ../../libtiff-v$LIBTIFF_VERSION/libtiff/.libs/$LIBTIFF_LIBNAME ]; then
    cp ../../libtiff-v$LIBTIFF_VERSION/libtiff/.libs/$LIBTIFF_LIBNAME .
fi

if [ ! -f lib/libproj.dylib ]; then

    cmake -DBUILD_APPS=OFF -DBUILD_PROJSYNC=OFF -DBUILD_CCT=OFF \
        -DBUILD_GEOD=OFF -DBUILD_GIE=OFF -DBUILD_PROJ=OFF -DBUILD_PROJINFO=OFF -DBUILD_PROJSYNC=OFF -DENABLE_CURL=OFF \
        -DSQLite3_INCLUDE_DIR=../../sqlite-autoconf-$SQLITE_VERSION_URL \
        -DSQLite3_LIBRARY=../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-$LIBRARY_DIR/sqlite3.o \
        -DSQLite3_VERSION=$SQLITE_VERSION \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_PREFIX_PATH=../../sqlite-autoconf-$SQLITE_VERSION_URL/sqlite-build-$LIBRARY_DIR \
        -DTIFF_INCLUDE_DIR=../../libtiff-v$LIBTIFF_VERSION/libtiff \
        -DTIFF_LIBRARY_RELEASE=$(pwd)/$LIBTIFF_LIBNAME ..
    cmake --build .
    install_name_tool -change "/usr/local/lib/libtiff.6.dylib" "@loader_path/libtiff.6.dylib" lib/libproj.25.$VERSION.dylib
fi

echo "DONE!!"

mkdir -p ../../../resources/$LIBRARY_DIR
if [ -f lib/libproj.dylib ]; then cp lib/libproj.25.$VERSION.dylib ../../../resources/$LIBRARY_DIR/libproj.dylib ; fi
if [ -f ../../libtiff-v$LIBTIFF_VERSION/libtiff/.libs/$LIBTIFF_LIBNAME ]; then cp ../../libtiff-v$LIBTIFF_VERSION/libtiff/.libs/$LIBTIFF_LIBNAME ../../../resources/$LIBRARY_DIR/ ; fi
cp data/proj.db ../../../resources/

# Cross-platform building on Darwin, not working
# if [ "$(uname -sm)" == "Darwin x86_64" ]; then
#     echo "Building for arm64";
#     if [ -f lib/libproj.dylib ]; then rm lib/libproj.dylib ; fi
#     cmake -DBUILD_SHARED_LIBS=OFF -DCMAKE_OSX_ARCHITECTURES="arm64" //
#     cmake --build .
#     LIBRARY_DIR=darwin-arm64;
#     mkdir -p ../../../resources/$LIBRARY_DIR
#     if [ -f lib/libproj.dylib ]; then cp lib/libproj.dylib ../../../resources/$LIBRARY_DIR ; fi
# fi

# if [ "$(uname -sm)" == "Darwin arm64" ]; then
#     echo "Building for x86_64";
#     if [ -f lib/libproj.dylib ]; then rm lib/libproj.dylib ; fi
#     cmake -DBUILD_SHARED_LIBS=OFF -DCMAKE_OSX_ARCHITECTURES="x86_64" //
#     cmake --build .
#     LIBRARY_DIR=darwin-x64;
#     mkdir -p ../../../resources/$LIBRARY_DIR
#     if [ -f lib/libproj.dylib ]; then cp lib/libproj.dylib ../../../resources/$LIBRARY_DIR ; fi
# fi
