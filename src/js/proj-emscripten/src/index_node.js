//import "core-js";
// import path from 'path';
// import { fileURLToPath } from 'url';

// const __filename = fileURLToPath(import.meta.url);
// const __dirname = path.dirname(__filename);
//

//import path from 'path';
//import { URL } from 'url';

//const __filename = import.meta.filename;
//const __dirname = import.meta.dirname;

//const __filename = new URL('', import.meta.url).pathname;
//const __dirname = new URL('', import.meta.url).pathname;

import { pn } from "./pn";
//import './pn.wasm';
//import { pj } from "./pj.js";

//import projwasmmodule from "./projwasm.wasm"
//import { projJs } from "./projjs.js"

// Since webpack will change the name and potentially the path of the
// `.wasm` file, we have to provide a `locateFile()` hook to redirect
// to the appropriate URL.
// More details: https://kripken.github.io/emscripten-site/docs/api_reference/module.html
// var pwasm = pw({
//     locateFile(path) {
//       if (path.endsWith(`.wasm`)) {
//         return projModule
//       }
//       return path
//     },
//   });

//var projjs = projjs

var proj = { proj: pn };

export default proj;
