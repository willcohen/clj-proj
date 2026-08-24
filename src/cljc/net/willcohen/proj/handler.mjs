// Copyright (c) 2024, 2025, 2026 Will Cohen
//
// Part of clj-proj, under the MIT License.
// See LICENSE for license information.
// SPDX-License-Identifier: MIT

import * as squint_core from 'squint-cljs/core.js';
import * as proj_loader from './proj-loader.mjs';
import * as pool from 'ffi-wasm/pool';
var default_init_args = async function (opts) {
const resources1 = (await proj_loader.loadProjResources());
return squint_core.js_obj("dbBytes", resources1.projDb, "iniBytes", resources1.projIni, "logLevel", (await (async () => {
const or__23674__auto__2 = squint_core.get(opts, "log-level");
if (squint_core.truth_(or__23674__auto__2)) {
return or__23674__auto__2} else {
return 0};

})()));

};
var pre_terminate_BANG_ = async function () {
(await pool.flush_pending_disposes_BANG_());
pool.reset_library_context_BANG_("net.willcohen.proj");
return null;

};
var spec = /* @__PURE__ */ (() => {
const impl41 = (function () {
return spec(null);

});
const impl52 = (function (args) {
return ({"module": (new URL("./proj-handler.mjs", import.meta.url)).href, "args": args, "pre-terminate": pre_terminate_BANG_});

});
const f1 = (function (...args2) {
const self63 = this;
const G__74 = args2.length;
switch (G__74) {case 0:
return impl41.call(self63);

break;
case 1:
return impl52.call(self63, args2[0]);

break;
default:
throw (new Error(`${"Invalid arity: "}${args2.length??''}`))};

});
return f1;

})();

export { default_init_args, pre_terminate_BANG_, spec }
