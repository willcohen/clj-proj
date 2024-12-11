#!/usr/bin/env sh

cd src/js/proj-emscripten

npm install

npx webpack --config ./webpack.config.web.js
npx webpack --config ./webpack.config.node.js
npx webpack --config ./webpack.config.graal.js

cp dist/proj_emscripten.graal.js ../../../resources/wasm/proj_emscripten.graal.js
cp dist/pw.wasm ../../../resources/wasm/pw.wasm
rm dist/proj_emscripten.graal.js
