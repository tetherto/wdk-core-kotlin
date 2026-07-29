package to.tether.wdk.core

data class EntropyResult(
    val encryptionKey: String,
    val encryptedSeedBuffer: String,
    val encryptedEntropyBuffer: String
)

data class SeedAndEntropyResult(
    val encryptionKey: String,
    val encryptedSeedBuffer: String,
    val encryptedEntropyBuffer: String
)
