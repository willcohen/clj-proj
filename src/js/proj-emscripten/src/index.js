//import "core-js";
import { pw } from "./pw";
//import { pi } from "./pi";
//import { pgi } from "./pgi";
//import './pw.wasm';
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

var proj = { proj: pw };

export default proj;
