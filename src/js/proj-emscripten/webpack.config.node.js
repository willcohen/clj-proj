module.exports = {
  experiments: {
    outputModule: true,
  },
  mode: 'development',
  entry: [
    //'./npm/projwasm/index.js'
    './src/index_node.js'
  ],
  output: {
    path: __dirname + '/dist',
    filename: "proj_emscripten.node.mjs",
    library: "proj_emscripten",
    publicPath: '',
    module: true,
    chunkFormat: "module",
    library: {
      type: "module"
    },
  },
  target: "node",
  // module: {
  //   rules: [
  //     {
  //       test: /pn\.wasm$/,
  //       type: "asset/resource",
  //       generator: {
  //         filename: "[name].wasm"
  //       }
  //     }
  //   ]
  // },
  node: {
    __filename: true,
    __dirname: true
  },
  // plugins: []
};
