/*
 * proj_network_stubs.c - C callback stubs for PROJ network access in GraalVM WASM.
 *
 * GraalVM's WASM engine uses nominal type checking for indirect function calls.
 * Emscripten's addFunction creates mini WASM wrapper modules whose type indices
 * differ from the main PROJ module. This causes RuntimeError on indirect call.
 *
 * These stubs are compiled directly into the PROJ WASM binary, giving them
 * correct module-local type indices. Each stub uses EM_JS to dispatch to
 * JavaScript globals set by Java ProxyExecutable callbacks.
 *
 * Data flow: PROJ -> C stubs -> EM_JS -> globalThis.__proj_net_* -> Java ProxyExecutable
 * The Java side (network.clj) sets the globalThis callbacks before calling
 * proj_setup_network_stubs, which registers these C stubs with PROJ.
 *
 * Debugging type mismatches: use `wasm-objdump -x proj-emscripten.wasm` to
 * inspect type section and verify stub function signatures match PROJ's
 * expected indirect call types.
 */

#include <proj.h>
#include <emscripten.h>
#include <stdint.h>

/* EM_JS bridge functions: C -> JS -> Java ProxyExecutable via globalThis.
 * The globalThis.__proj_net_* functions are set by Java code in network.clj
 * (GraalVM) before proj_setup_network_stubs is called. */

EM_JS(int, js_net_open, (int ctx, const char *url_ptr, double offset,
      int size_to_read, int buffer_ptr, int out_size_ptr,
      int err_max_size, int err_str_ptr, int user_data), {
    if (globalThis.__proj_net_open) {
        return globalThis.__proj_net_open(ctx, url_ptr, offset, size_to_read,
            buffer_ptr, out_size_ptr, err_max_size, err_str_ptr, user_data);
    }
    return 0;
});

EM_JS(void, js_net_close, (int ctx, int handle, int user_data), {
    if (globalThis.__proj_net_close) {
        globalThis.__proj_net_close(ctx, handle, user_data);
    }
});

EM_JS(int, js_net_get_header, (int ctx, int handle, const char *name_ptr,
      int user_data), {
    if (globalThis.__proj_net_get_header) {
        return globalThis.__proj_net_get_header(ctx, handle, name_ptr, user_data);
    }
    return 0;
});

EM_JS(int, js_net_read_range, (int ctx, int handle, double offset,
      int size_to_read, int buffer_ptr, int err_max_size, int err_str_ptr,
      int user_data), {
    if (globalThis.__proj_net_read_range) {
        return globalThis.__proj_net_read_range(ctx, handle, offset,
            size_to_read, buffer_ptr, err_max_size, err_str_ptr, user_data);
    }
    return 0;
});

/* C stubs matching PROJ's expected callback signatures.
 * Compiled into the main WASM module => correct type indices for indirect calls.
 *
 * The double casts (e.g. (int)(intptr_t)ptr) are required because:
 * - EM_JS only supports int and double parameter types
 * - WASM32 pointers are always i32, so (int)(intptr_t) is safe
 * - unsigned long long offset is cast to double because EM_JS has no i64 */

static PROJ_NETWORK_HANDLE *stub_open(
    PJ_CONTEXT *ctx, const char *url, unsigned long long offset,
    size_t size_to_read, void *buffer, size_t *out_size_read,
    size_t error_string_max_size, char *out_error_string, void *user_data) {
    int result = js_net_open((int)(intptr_t)ctx, url, (double)offset,
        (int)size_to_read, (int)(intptr_t)buffer, (int)(intptr_t)out_size_read,
        (int)error_string_max_size, (int)(intptr_t)out_error_string,
        (int)(intptr_t)user_data);
    return (PROJ_NETWORK_HANDLE *)(intptr_t)result;
}

static void stub_close(PJ_CONTEXT *ctx, PROJ_NETWORK_HANDLE *handle,
                        void *user_data) {
    js_net_close((int)(intptr_t)ctx, (int)(intptr_t)handle,
                 (int)(intptr_t)user_data);
}

static const char *stub_get_header(PJ_CONTEXT *ctx,
    PROJ_NETWORK_HANDLE *handle, const char *header_name, void *user_data) {
    int result = js_net_get_header((int)(intptr_t)ctx, (int)(intptr_t)handle,
        header_name, (int)(intptr_t)user_data);
    return (const char *)(intptr_t)result;
}

static size_t stub_read_range(PJ_CONTEXT *ctx, PROJ_NETWORK_HANDLE *handle,
    unsigned long long offset, size_t size_to_read, void *buffer,
    size_t error_string_max_size, char *out_error_string, void *user_data) {
    return (size_t)js_net_read_range((int)(intptr_t)ctx, (int)(intptr_t)handle,
        (double)offset, (int)size_to_read, (int)(intptr_t)buffer,
        (int)error_string_max_size, (int)(intptr_t)out_error_string,
        (int)(intptr_t)user_data);
}

/* Single entry point called from Java via ccall("proj_setup_network_stubs", "number", ["number"], [ctx_ptr]).
 * Registers the four C stubs with PROJ's network callback system. */
EMSCRIPTEN_KEEPALIVE
int proj_setup_network_stubs(PJ_CONTEXT *ctx) {
    return proj_context_set_network_callbacks(
        ctx, stub_open, stub_close, stub_get_header, stub_read_range, NULL);
}
