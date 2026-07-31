# WDK Worklet Bundle (`js/`)

This folder holds the JavaScript source and configuration for the **WDK worklet** —
the JS program that runs inside BareKit on the device. `npm run generate` compiles
everything here into a single binary bundle (`.wdk-bundle/wdk-worklet.bundle`) plus
the native addons, which the Android build then copies into the library (see
[build.gradle](../build.gradle)).

You normally only touch **one file**: [`wdk.config.js`](./wdk.config.js). Everything
else is produced by the two upstream packages below.

## The two upstream packages

| Package | Role | When it runs |
| ------- | ---- | ------------ |
| [`@tetherto/pear-wrk-wdk`](https://github.com/tetherto/pear-wrk-wdk) | The worklet **runtime**. Runs *inside* Bare/BareKit on-device. Provides the JSON-RPC handler layer (`registerJsonRpcHandlers`) and the wallet/protocol registration that `WdkCore.kt` talks to over IPC. | On-device, at runtime |
| [`@tetherto/wdk-worklet-bundler`](https://github.com/tetherto/wdk-worklet-bundler) | The build-time **CLI**. Reads `wdk.config.js`, generates the worklet entry point (wiring in `pear-wrk-wdk`), runs `bare-pack` to produce the `.bundle`, links the native addons, and (for `jsonrpc`) converts the bundle's ESM to CJS. | On your machine, `npm run generate` |

So: **`pear-wrk-wdk` is what the bundle *is*; `wdk-worklet-bundler` is what *builds* it.**
To change *behavior* (handlers, wallet APIs) you look upstream at `pear-wrk-wdk`; to change
*what gets packaged and how* you edit `wdk.config.js` and re-run the bundler.

> **Note — bundler is currently pinned to a fork.** [`package.json`](./package.json)
> points `@tetherto/wdk-worklet-bundler` at
> `github:claudiovb/wdk-worklet-bundler#fix/esmcjs`. That branch fixes a worklet bug where
> the CJS `.mjs` loader was gated to iOS/macOS only, so Android (and any QuickJS build)
> failed at runtime with `module is not defined`. Repoint this to the upstream release once
> the fix is merged.

## Commands

```bash
npm install         # install pear-wrk-wdk, the wallet packages, and the bundler
npm run generate    # build .wdk-bundle/wdk-worklet.bundle + android-addons/
npm run clean       # remove generated artifacts
```

`npm run generate` runs `wdk-worklet-bundler generate --install --verbose`. Its output:

- `.wdk-bundle/wdk-worklet.bundle` — the binary worklet bundle
- `android-addons/` — the per-ABI native addon `.so` files

The Gradle `preBuild` task copies these to `src/main/assets/wdk.bundle` and
`src/main/addons/` on every build, so a normal `./gradlew assemble` regenerates and
picks up the latest bundle automatically.

## Editing `wdk.config.js`

```js
module.exports = {
  // Transport. Keep 'jsonrpc' for Kotlin/BareKit (the bundle is loaded as a binary
  // blob over length-prefixed IPC). 'hrpc' is for React Native (JS-module import).
  transport: 'jsonrpc',

  // Networks: map a logical network name -> the WDK wallet package that backs it.
  // Add/remove entries to change which chains the worklet supports. Fewer packages
  // = smaller bundle.
  networks: {
    ethereum:           { package: '@tetherto/wdk-wallet-evm' },
    polygon:            { package: '@tetherto/wdk-wallet-evm' },
    arbitrum:           { package: '@tetherto/wdk-wallet-evm' },
    sepolia:            { package: '@tetherto/wdk-wallet-evm' },
    'ethereum-erc4337': { package: '@tetherto/wdk-wallet-evm-erc-4337' },
    solana:             { package: '@tetherto/wdk-wallet-solana' },
    bitcoin:            { package: '@tetherto/wdk-wallet-btc' }
  },

  // Optional protocol modules (same shape as networks).
  protocols: {},

  output: {
    bundle:  './.wdk-bundle/wdk-worklet.bundle',
    addons:  { android: './android-addons' }
  },

  options: {
    linkAddons:      true,                       // link native addons (default true for jsonrpc)
    platforms:       ['android'],                // which platforms to link addons for
    targets:         ['android-arm64', 'android-arm', 'android-ia32', 'android-x64'],
    convertEsmToCjs: true                        // see below — keep true for Android
  }
}
```

Any package you reference in `networks`/`protocols` must also be a dependency in
[`package.json`](./package.json) so `npm install` can resolve it.

### `convertEsmToCjs` — why it must stay `true` here

Different JS engines support different module systems:

| Engine | Used by | ESM? | CJS? |
| ------ | ------- | ---- | ---- |
| V8     | Heavier with JIT BareKit build - faster runtime on potent hardware | ✅ | ✅ |
| QuickJS | current Android BareKit build recommended for this repo (`libbare-kit.so`) - 60 MB less | ❌ | ✅ |
| JSC    | iOS / macOS | ❌ | ✅ |

CJS runs on **all three**; raw ESM only runs on V8. `convertEsmToCjs: true` makes the
bundler run esbuild over the bundle's `.js`/`.mjs`/`.cjs` files and emit CJS, so the same
bundle works on QuickJS, JSC and V8 alike. Leave it `true`.

Only set it to `false` if you *knowingly* ship a V8-only build and want to keep raw ESM — which is not the case for the QuickJS Android
runtime this repo currently uses.
