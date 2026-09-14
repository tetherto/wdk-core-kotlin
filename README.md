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
### Step 2: BareKit Android Runtime (fetched automatically)

The library depends on Holepunch's BareKit Android runtime — a `classes.jar` to compile against plus per-ABI native `.so` libraries. These are **not committed** here (hundreds of MB), and you normally don't provide them by hand: the `fetchBareKit` Gradle task downloads the latest [`holepunchto/bare-kit`](https://github.com/holepunchto/bare-kit/releases) release and extracts just the Android files into `libs/bare-kit/`. No secrets or auth — it's a public release.

It's a **one-time, on-demand** fetch: it runs only when `libs/bare-kit/classes.jar` is missing, and **never during an Android Studio Gradle _sync_** (only during an actual build). So on a fresh clone the `to.holepunch.bare.kit.*` imports show unresolved until your first `./gradlew` build populates them — run one build (or Build ▸ Make if on Android Studio) and they resolve for good.

Override with Gradle properties:

| Property | Default | Effect |
| -------- | ------- | ------ |
| `-PbareKitEngine=<engine>` | `v8` | Which JS-engine build to fetch. `v8` uses the archive's `android/` dir; any other value uses `android-<engine>/` (e.g. a future `quickjs`). If the archive has no such dir the build fails listing what's available. See [`js/README.md`](js/README.md) for engine trade-offs. |
| `-PbareKitTag=<tag>` | latest | Pin a specific BareKit release instead of the latest. |
| `-PbareKitDir=<path>` | `libs/bare-kit` | Use a pre-provisioned copy (air-gapped, or your own local BareKit build) instead of downloading. |

To force a re-fetch — e.g. after changing `-PbareKitTag`/`-PbareKitEngine` — delete `libs/bare-kit/`.

<details>
<summary>Manual placement (offline / local build)</summary>

If you'd rather supply it yourself, drop the BareKit Android files under `libs/bare-kit/` (or wherever `-PbareKitDir` points) in this layout; `fetchBareKit` then finds the `classes.jar` and skips:

```
libs/bare-kit/
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

`build.gradle` wires these in via `jniLibs.srcDirs` (the `.so`) and `api files('libs/bare-kit/classes.jar') { builtBy 'fetchBareKit' }` (the compile classpath), so the fetch runs before compilation automatically.

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
