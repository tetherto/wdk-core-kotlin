# wdk-core-kotlin

An Android library for the [Tether WDK](https://github.com/Tetherto/wdk) (Wallet Development Kit). Provides a clean coroutine-based Kotlin API for wallet operations, key management, and multi-chain interactions on Android.

Supported networks: check wdk-wallet-* [implementations](https://docs.wdk.tether.io/sdk/wallet-modules/#wallet-modules), default is the ones currently set on wdk.config.js but you can customise to any chain you need.

## Integration Guide

### Step 1: Add the Library

**Once published to Maven Central** (planned):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// app/build.gradle.kts
dependencies {
    implementation("to.tether.wdk:core-kotlin:1.0.0")
}
```

**For now (local build):**

Clone this repository alongside your app and include it as a Gradle module:

```kotlin
// settings.gradle.kts
include(":app")
include(":wdk-core-kotlin")
project(":wdk-core-kotlin").projectDir = file("../wdk-core-kotlin")

// app/build.gradle.kts
dependencies {
    implementation(project(":wdk-core-kotlin"))
}
```
> Note: See build.gradle from wdk-starter-kotlin for a [reference](https://github.com/Tetherto/wdk-starter-kotlin)
### Step 2: BareKit Android Runtime (provisioned automatically)

The library depends on Holepunch's BareKit Android runtime — a `classes.jar` to compile against plus per-ABI native `.so` libraries (BareKit's Android AAR, unpacked). These are **not committed** here (hundreds of MB), and you normally don't provide them by hand: the `fetchBareKit` Gradle task (in [`gradle/bare-kit.gradle`](gradle/bare-kit.gradle)) provisions them into `libs/bare-kit/`, by default from the latest [`holepunchto/bare-kit`](https://github.com/holepunchto/bare-kit/releases) public release. No secrets or auth.

It's **on-demand**: it runs only when `libs/bare-kit/classes.jar` is missing, or when the engine/tag you ask for differs from what's already there (recorded in `libs/bare-kit/.bare-kit-source`). Same request → no-op, so day-to-day builds cost nothing. It **never runs during an Android Studio Gradle _sync_** (only during an actual build), so on a fresh clone the `to.holepunch.bare.kit.*` imports show unresolved until your first `./gradlew` build populates them — run one build (or Build ▸ Make) and they resolve for good.

#### Choosing the JS engine

BareKit's JavaScript engine is a build-time choice. Both run the same CJS worklet bundle (`convertEsmToCjs: true`, see [`js/README.md`](js/README.md)); pick by size vs. speed:

| Engine | `libbare-kit.so` per ABI | JIT | Notes |
| ------ | ------------------------ | --- | ----- |
| **V8** (default) | ~65 MB | yes | Fastest on the pure-JavaScript crypto paths (curve math and PBKDF2 in the wallet packages run in JS). |
| **QuickJS** | ~3.8 MB (~60 MB smaller) | no | Validated end-to-end on this library's instrumented suite. Those same JS-heavy crypto paths pay the no-JIT cost — a benchmark on low-end hardware is a pending follow-up, so no performance claim is made here. |

Holepunch's releases currently ship **V8 only** for Android. QuickJS (or any other engine) is provisioned in tiers — the first that works wins:

1. **`-PbareKitDir=<path>`** — use a prebuilt copy (e.g. a QuickJS build shared with you). We never modify or delete a directory you point us at; a mismatching engine is an error.
2. **Upstream release** — `android/` for V8, `android-<engine>/` for others (upstream's own naming for alternate engines). Works for V8 today; for other engines it starts working the day upstream ships them, and tier 3 goes dormant by itself.
3. **Local source build** (opt-in) — `-PbareKitAllowSourceBuild=true`. Clones `bare-kit` at the *same release tag*, builds its Android AAR with the requested engine (`BARE_ENGINE`, one CMake flag, injected via [`gradle/bare-kit-engine.init.gradle`](gradle/bare-kit-engine.init.gradle) so no upstream file is edited), unpacks it into `libs/bare-kit/` and deletes the build tree.

```bash
# QuickJS, built locally because upstream has no Android QuickJS prebuild yet
./gradlew assembleDebug -PbareKitEngine=quickjs -PbareKitAllowSourceBuild=true
```

The source build needs the **Android NDK version bare-kit pins** (read from its `android/build.gradle`), **CMake**, **git**, and a **Node.js new enough for bare-kit's build tooling** (its native module lexer segfaults on older ones — currently `^22.21 || >=24.9`; the tooling's `npm install` declares the exact range and preflight fails fast on a mismatch). The nested build uses whatever `node` a login shell puts on PATH, so `nvm use <version>` in the shell you launch Gradle from is enough — it propagates — or make it your nvm default. It uses **up to ~4 GB of temporary disk** (3.9 GB measured for all four ABIs) under `build/bare-kit-src/` (deleted afterwards, whatever happens) for **~15–30 minutes, once** (16 min on an Apple-silicon Mac). If the nested build fails once it is retried incrementally — upstream's CMake has a generated-header ordering race a second pass resolves; a real error fails both passes. It checks those up front and fails in seconds naming exactly what to install. If [Socket Firewall](https://socket.dev) (`sfw`) is installed, `SOCKET_API_TOKEN` (or `SOCKET_API_KEY`) must be **exported as an environment variable** in the environment Gradle runs in — bare-kit's dependency installer calls the `sfw` binary directly for its nested `npm install`s, so a shell alias that inlines the key never reaches it, and `sfw` refuses to run without one (preflight catches this too). Keep the key out of the repo: never put it in the project's `gradle.properties`. Without the flag you get an actionable error instead of a surprise 20-minute build.

Gradle properties:

| Property | Default | Effect |
| -------- | ------- | ------ |
| `-PbareKitEngine=<engine>` | `v8` | `v8`, `quickjs`, or a raw `github:owner/repo` `BARE_ENGINE` value (so an engine upstream adds tomorrow works with no code change). Unknown names fail instantly, listing the valid ones. |
| `-PbareKitAllowSourceBuild=true` | off | Opt in to tier 3 above when the engine isn't in the upstream release. |
| `-PbareKitTag=<tag>` | latest | Pin a specific BareKit release (applies to both the download and the source build). |
| `-PbareKitDir=<path>` | `libs/bare-kit` | Use a pre-provisioned copy (air-gapped, a shared prebuilt, or your own build). Never modified by us. |

Switching `-PbareKitEngine` or `-PbareKitTag` re-provisions `libs/bare-kit/` automatically — the old contents are replaced only once the new build is in hand, so a failed attempt never leaves you without libs. The ~400 MB upstream archive and any source-built AAR (`bare-kit-<engine>-<tag>.aar`) are cached under `build/` (reclaimed by `./gradlew clean`), so switching back and forth neither re-downloads nor rebuilds.

**Pass `-PbareKitEngine` on every invocation** — a plain `./gradlew build` means `v8`, and the engine mismatch will swap `libs/bare-kit/` back (cheaply, thanks to the caches, but still). To make an engine the project default, set it in `gradle.properties` — `bareKitEngine=quickjs`, plus `bareKitAllowSourceBuild=true` if you accept the source build — and `-P` still overrides per run. With no tag pinned, an already-provisioned dir is **not** refreshed when upstream publishes a new release: delete `libs/bare-kit/` (or pass `-PbareKitTag`) to move.

<details>
<summary>Manual placement (offline / your own build)</summary>

If you'd rather supply it yourself, drop the BareKit Android files under `libs/bare-kit/` (or wherever `-PbareKitDir` points) in this layout; `fetchBareKit` then finds the `classes.jar` and skips. Add a `.bare-kit-source` with at least `engine=<name>` so engine switching knows what's there (a dir without one is assumed to be V8):

```
libs/bare-kit/
├── .bare-kit-source      # engine=quickjs, tag=v2.5.5, source=local-build|upstream-release
├── AndroidManifest.xml
├── classes.jar
└── jni/
    ├── arm64-v8a/
    │   ├── libbare-kit.so
    │   └── libc++_shared.so
    ├── armeabi-v7a/
    │   ├── libbare-kit.so
    │   └── libc++_shared.so
    ├── x86/
    │   ├── libbare-kit.so
    │   └── libc++_shared.so
    └── x86_64/
        ├── libbare-kit.so
        └── libc++_shared.so
```
</details>

`build.gradle` wires these in via `jniLibs.srcDirs` (the `.so`) and `api files(bareKitClassesJar) { builtBy 'fetchBareKit' }` (the compile classpath), so provisioning runs before compilation automatically.

### Step 3: Build

That's it — build the library and the JS worklet bundle is generated and packaged for you:

```bash
./gradlew assemble
```

The build runs the JS bundler automatically (`generateBundle` → `npm run generate`) and
copies the result into the AAR. Gradle's up-to-date checking keeps it cheap:

- Edit `js/wdk.config.js` (networks/wallet packages) → the next build regenerates the bundle.
- Edit only Kotlin → the whole JS pipeline is skipped.

<details>
<summary>Iterating on the JS by hand (optional)</summary>

You rarely need this, but to build the bundle yourself:

```bash
cd js
npm install
npm run generate   # → js/.wdk-bundle/wdk-worklet.bundle + js/android-addons/
```
</details>

To customize networks or wallet packages, edit `js/wdk.config.js` — **see [`js/README.md`](js/README.md)**
for the full walkthrough and how `pear-wrk-wdk` and `wdk-worklet-bundler` fit together.

## Quick Start

```kotlin
import to.tether.wdk.core.WdkCore
import kotlinx.coroutines.runBlocking

val wdk = WdkCore(context)

runBlocking {
    // Create a new wallet
    val entropy = wdk.generateEntropyAndEncrypt(wordCount = 12)

    // Show the mnemonic to the user for backup
    val mnemonic = wdk.getMnemonicFromEntropy(
        encryptedEntropy = entropy.encryptedEntropyBuffer,
        encryptionKey = entropy.encryptionKey
    )
    println("Backup phrase: $mnemonic")

    // Initialize WDK with network configuration
    val config = """
        {
          "networks": {
            "ethereum": { "rpcUrl": "https://eth-mainnet.example.com" }
          }
        }
    """.trimIndent()

    wdk.initializeWDK(
        encryptionKey = entropy.encryptionKey,
        encryptedSeed = entropy.encryptedSeedBuffer,
        config = config
    )

    // Get an address
    val address = wdk.getAddress(network = "ethereum")
    println("Address: $address")

    // Get a balance
    val balance = wdk.getBalance(network = "ethereum")
    println("Balance: $balance")

    // Clean up when done
    wdk.dispose()
}

// Release native resources
wdk.close()
```

`WdkCore` implements `Closeable`, so it works with `use { }` for automatic cleanup.

## API Reference

### Initialization

```kotlin
// Loads wdk.bundle from the library's assets
val wdk = WdkCore(context)
```

### Wallet Lifecycle

| Method | Description |
| ------ | ----------- |
| `generateEntropyAndEncrypt(wordCount)` | Generate a new mnemonic (12 or 24 words) and return encrypted entropy |
| `getMnemonicFromEntropy(encryptedEntropy, encryptionKey)` | Decrypt entropy to recover the mnemonic phrase |
| `getSeedAndEntropyFromMnemonic(mnemonic)` | Convert an existing mnemonic to encrypted seed + entropy |
| `initializeWDK(encryptionKey, encryptedSeed, config)` | Initialize WDK with keys and network configuration |
| `dispose()` | Clean up worklet-side resources |
| `close()` | Release native IPC and worklet resources (called automatically with `use { }`) |

### Account Operations

| Method | Description |
| ------ | ----------- |
| `getAddress(network, accountIndex)` | Get the account address for a network |
| `getBalance(network, accountIndex)` | Get the account balance for a network |
| `callMethod(methodName, network, accountIndex, args, options)` | Call any WDK method on an account |

### Dynamic Registration

| Method | Description |
| ------ | ----------- |
| `registerWallet(config)` | Register additional wallet types at runtime |
| `registerProtocol(config)` | Register additional protocols at runtime |

### Lifecycle Helpers

| Method | Description |
| ------ | ----------- |
| `suspend()` | Suspend the worklet (e.g. when the host app moves to background) |
| `resume()` | Resume a suspended worklet |

## Error Handling

Every suspending (coroutine) method can throw `WdkError` on failure — a sealed class with three cases. (This is unrelated to the `suspend()` lifecycle method above, which just pauses the worklet and does not throw.)

```kotlin
sealed class WdkError(message: String) : Exception(message) {
    class IpcError(message: String) : WdkError("IPC Error: $message")
    class RpcError(val code: String, override val message: String) : WdkError("RPC Error [$code]: $message")
    class InvalidResponse(message: String) : WdkError("Invalid Response: $message")
}
```

| Case | When it's thrown |
| ---- | ---------------- |
| `IpcError` | Communication failure with the worklet (transport-level) |
| `RpcError` | Error returned by the WDK worklet (carries `code` and `message`) |
| `InvalidResponse` | Worklet returned a payload in an unexpected shape |

## Custom Worklet Bundle

If you need a custom worklet with different WDK modules or network configurations, edit `js/wdk.config.js`:

```js
module.exports = {
  transport: 'jsonrpc',
  networks: {
    ethereum: { package: '@tetherto/wdk-wallet-evm' },
    solana:   { package: '@tetherto/wdk-wallet-solana' },
    bitcoin:  { package: '@tetherto/wdk-wallet-btc' },
    // add or remove networks here
  },
  protocols: {},
  output: {
    bundle: './.wdk-bundle/wdk-worklet.bundle',
    addons: { android: './android-addons' }
  },
  options: {
    linkAddons: true,
    platforms: ['android'],
    targets: ['android-arm64', 'android-arm', 'android-ia32', 'android-x64'],
    convertEsmToCjs: true // keep true: QuickJS (and iOS JSC) only run CJS, not ESM
  }
}
```

Then rerun `npm run generate` and rebuild the AAR. For a field-by-field explanation (especially `convertEsmToCjs` and the engine differences), see [`js/README.md`](js/README.md).

## Architecture

```
Your App
  │
  ├── WdkCore (Kotlin, coroutine API)
  │     │
  │     └── JSON-RPC 2.0 over length-prefixed IPC
  │           │
  │           └── BareKit (Worklet + IPC)
  │                 │
  │                 ├── wdk.bundle (JavaScript worklet)
  │                 │
  │                 └── Native addons in src/main/addons/
  │                       (crypto, networking, filesystem, etc.)
  │
  └── BareKit Android runtime (libs/bare-kit/)
```

## Requirements

- Android `minSdk` 31, `compileSdk` 35
- Kotlin 1.9.22+ with coroutines
- JDK 17
- Node.js (for building the worklet bundle)
- Android NDK + supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## License

Apache-2.0
