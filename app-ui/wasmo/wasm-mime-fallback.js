// Wasmo serves .wasm without an application/wasm content type, which the streaming WebAssembly
// APIs reject. Fall back to compiling from the downloaded bytes for those responses only.
(() => {
  const isWasm = (response) => (response.headers.get('content-type') ?? '').startsWith('application/wasm');
  const { instantiateStreaming, compileStreaming } = WebAssembly;
  WebAssembly.instantiateStreaming = async (source, imports) => {
    const response = await source;
    if (isWasm(response)) return instantiateStreaming(response, imports);
    return WebAssembly.instantiate(await response.arrayBuffer(), imports);
  };
  WebAssembly.compileStreaming = async (source) => {
    const response = await source;
    if (isWasm(response)) return compileStreaming(response);
    return WebAssembly.compile(await response.arrayBuffer());
  };
})();
