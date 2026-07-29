# wdk-core-kotlin

An Android library for the [Tether WDK](https://github.com/Tetherto/wdk) (Wallet Development Kit). Provides a clean coroutine-based Kotlin API for wallet operations, key management, and multi-chain interactions on Android.

Supported networks: Ethereum, Polygon, Arbitrum, Sepolia, Solana, Bitcoin, and ERC-4337.

## Integration Guide

> Note: this repository does not yet ship a published JSON-RPC worklet release (the Android equivalent of the Swift package's `prebuilds.zip` / `addons.zip`). Until that is available, integration is done by building the library locally. The steps below cover both paths.

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

### Step 2: Provide the BareKit Android Runtime

The library depends on Holepunch's BareKit Android runtime (a `classes.jar` plus per-ABI `.so` libraries). These are **not committed** to this repository because of their size (~230 MB).

Place the BareKit AAR contents under `libs/bare-kit/` in this layout:

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

`build.gradle` wires these in automatically via `jniLibs.srcDirs` and a conditional `api files('libs/bare-kit/classes.jar')`.

### Step 3: Build the WDK Worklet Bundle

The JavaScript worklet that runs inside BareKit is generated from `js/`:

```bash
cd js
npm install
npm run generate
```

This produces:

- `js/.wdk-bundle/wdk-worklet.bundle` (the worklet bytecode)
- `js/android-addons/` (native addon `.so` files for each ABI)

The `preBuild` Gradle task copies these into `src/main/assets/wdk.bundle` and `src/main/addons/` respectively, so a normal `./gradlew assemble` will produce a working AAR.

To customize networks or wallet packages, edit `js/wdk.config.js` before running `npm run generate`.

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

All suspend methods throw `WdkError`, a sealed class with three cases:

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
    convertEsmToCjs: false
  }
}
```

Then rerun `npm run generate` and rebuild the AAR.

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

- Android `minSdk` 33, `compileSdk` 35
- Kotlin 1.9.22+ with coroutines
- JDK 17
- Node.js (for building the worklet bundle)
- Android NDK + supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

## License

Apache-2.0
