# WDK Worklet Bundle (`js/`)

This folder holds the JavaScript source and configuration for the **WDK worklet** -
the JS program that runs inside BareKit on the device - it's bundled through `wdk-worklet-bundler`. 
`npm run generate` compiles everything here into a single binary bundle (`.wdk-bundle/wdk-worklet.bundle`) plus
the native addons due to the addons config in `wdk.config.js`, which the Android build then copies into the library (see
[build.gradle](../build.gradle)).

You normally only touch **one file**: [`wdk.config.js`](./wdk.config.js). Everything
else is produced by the two upstream packages below.

## The two upstream packages

| Package | Role | When it runs |
| ------- | ---- | ------------ |
| [`@tetherto/pear-wrk-wdk`](https://github.com/tetherto/pear-wrk-wdk) | The worklet **runtime**. Runs *inside* Bare/BareKit on-device. Provides the JSON-RPC handler layer (`registerJsonRpcHandlers`) and the wallet/protocol registration that `WdkCore.kt` talks to over IPC. | On-device, at runtime |
| [`@tetherto/wdk-worklet-bundler`](https://github.com/tetherto/wdk-worklet-bundler) | The build-time **CLI**. Reads `wdk.config.js`, generates the worklet entry point (wiring in `pear-wrk-wdk`), runs `bare-pack` to produce the `.bundle`, links the native addons. | On your machine, `npm run generate` |

So: **`pear-wrk-wdk` is what the bundle *is*; `wdk-worklet-bundler` is what *builds* it.**
To change *behavior* (handlers, wallet APIs) you look upstream at `pear-wrk-wdk`; to change
*what gets packaged and how* you edit `wdk.config.js` and re-run the bundler.

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
picks up the latest bundle automatically - meaning you can just run `./gradlew assemble` and never touch here unless you want to customise.

## Editing `wdk.config.js`

```js
module.exports = {
  // Transport. Keep 'jsonrpc' for this library (the bundle is loaded as a binary blob
  // over length-prefixed IPC). 'hrpc' is the React Native / Swift transport. JSON-RPC is
  // what the pinned beta.17 line speaks; the bare-stow line replaces it with bare-rpc/HRPC.
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
    // addon platforms derive from `targets` (android-* → android); there is no `platforms` option
    targets:         ['android-arm64', 'android-arm', 'android-ia32', 'android-x64'], // which device architecture you target
    convertEsmToCjs: true                        // see below — keep true
  }
}
```

Any package you reference in `networks`/`protocols` must also be a dependency in
[`package.json`](./package.json) so `npm install` can resolve it.

### `convertEsmToCjs` — why it stays `true`

Different JS engines support different module systems:

| Engine | Where | ESM? | CJS? |
| ------ | ----- | ---- | ---- |
| V8     | **This library** — the engine in every BareKit 2.x Android prebuild (`libbare-kit.so` ~65 MB per ABI, JIT) | ✅ | ✅ |
| JSC    | `wdk-core-swift` runs V8 by default and offers JSC through a documented switch; discouraged, and the consuming team owns the known engine issues (e.g. the iOS 26 crash in tetherto/wdk-core-swift#22) | ❌ | ✅ |
| QuickJS | No upstream Android prebuild; only via your own BareKit build (root README, Step 2) | ❌ | ✅ |

`convertEsmToCjs: true` makes the bundler run esbuild over the bundle's `.js`/`.mjs`/`.cjs` files and
emit CommonJS. V8 runs that bundle as-is, and it is the only form a CJS-only engine can run, so the
same bundle works everywhere. Leave it `true` — and do not remove it to work around a bundle
validation error: those come from dependency drift, see the pinned dependencies below.

### Which engine you actually get

**V8, always.** `build.gradle`'s `fetchBareKit` task pulls the newest BareKit 2.x release and
extracts its Android (V8) prebuild; there is no engine switch. QuickJS or JSC on Android means
building BareKit 2.x yourself and pointing `-PbareKitDir` at the result — unsupported, not re-tested
since 2026-11-09, and V8 is the recommendation until [bare-stow](https://github.com/holepunchto/bare-stow)
manages engine selection (roadmap: [tetherto/wdk-worklet-bundler#84](https://github.com/tetherto/wdk-worklet-bundler/issues/84), phase 3b).

## Pinned dependencies

[`package.json`](./package.json) pins on purpose. Do not "upgrade" these without re-running the
instrumented suite on a device:

- **`@tetherto/wdk-worklet-bundler` and `@tetherto/pear-wrk-wdk` at exactly `1.0.0-beta.17`.** These
  are the last releases of the JSON-RPC line. Later releases move to bare-stow and drop the JSON-RPC
  transport this library speaks, so a caret range would break the build or the worklet at boot.
  `package-lock.json` is committed so a fresh clone resolves the same wallet packages too, and it is
  a Gradle input: a lockfile-only bump (Dependabot, `npm audit fix`) re-runs `npmInstall` and
  `generateBundle` on an existing checkout instead of leaving a stale bundle behind.
- **`overrides.bare-node-runtime = 1.5.0`.** From 1.5.1 the runtime's globals eagerly load
  `bare-worker/global` (`BroadcastChannel`, `MessageChannel`), which drags in `bare-thread` and a newer
  `bare-module` than BareKit 2.x embeds: the worklet aborts at its first request with
  `ModuleTraverseError: ADDON_NOT_FOUND` (tetherto/wdk-starter-kotlin#16). The pin is required on this
  BareKit line, V8 included. It also matters for anyone still on the legacy QuickJS path:
  `BroadcastChannel` relies on N-API threading features that are experimental and not available in a
  QuickJS build, so 1.5.1+ crashes there outright. QuickJS has not been re-tested since 2026-11-09 (it
  passed then); we recommend V8 only until bare-stow upgrades this safely. The wallet packages declare
  `bare-node-runtime ^1.5.2` but run fine on 1.5.0 — the full instrumented suite passes.
