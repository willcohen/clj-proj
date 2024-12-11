module.exports = {
  experiments: {
    outputModule: true,
  },
  mode: 'development',
  entry: [
    //'./npm/projwasm/index.js'
    './src/index.js'
  ],
  output: {
    path: __dirname + '/dist',
    filename: "proj_emscripten.web.mjs",
    library: "proj_emscripten",
    publicPath: '',
    module: true,
    library: {
      type: "module"
    },
  },
  target: "web",
  module: {
    rules: [
      {
        test: /pw\.js$/,
        loader: "exports-loader",
        options: {
          type: "module",
          exports: "pw",
        },
      },
      {
        test: /pi\.js$/,
        loader: "exports-loader",
        options: {
          type: "module",
          exports: "pi",
        },
      },
      {
        test: /pgi\.js$/,
        loader: "exports-loader",
        options: {
          type: "module",
          exports: "pgi",
        },
      },
      {
        test: /pw\.wasm$/,
        type: "asset/resource",
        generator: {
          filename: "[name].wasm"
        }
      },
      {
        test: /proj\.db$/,
        type: "asset/resource",
        generator: {
          filename: "[name].db"
        }
      }
      // {
      //   test: /projjs\.js$/,
      //   loader: "exports-loader",
      //   options: {
      //     type: "module",
      //     exports: "projjs",
      //   },
      // }
      // {
      //   test: /\.js$/,
      //   exclude: /node_modules/,
      //   use: {
      //     loader: "babel-loader"
      //   }
      //}
    ]
  },
  resolve: {
    fallback: {
      "fs": false,
      "node:fs": "{}",
      "url": require.resolve("url/")
    }
  }
  // plugins: []
};
