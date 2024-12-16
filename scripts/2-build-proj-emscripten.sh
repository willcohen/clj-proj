#!/usr/bin/env sh

cd src/js/proj-emscripten

npm install

#sed -i 's/scriptDirectory = __dirname + /scriptDirectory = /g' src/pn.js

#npx webpack --config ./webpack.config.web.js
#npx webpack --config ./webpack.config.node.js
npx webpack --config ./webpack.config.graal.js

cp dist/proj_emscripten.graal.js ../../../resources/wasm/proj_emscripten.graal.js
rm dist/proj_emscripten.graal.js
