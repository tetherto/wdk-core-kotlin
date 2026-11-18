package to.tether.wdk.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A frame the reader loop rejects ends it for good: no response can arrive afterwards. Calls in
 * flight at that moment and calls made later must fail with the error that ended the loop
 * instead of waiting forever. Mirrors wdk-core-swift's test for tetherto/wdk-core-swift#11.
 */
@RunWith(AndroidJUnit4::class)
class WdkCoreReaderFailureTest {

    /**
     * How [call] ended: the message of the error it threw, or a note that it returned or is still
     * pending. A pending call would otherwise hang the test run instead of failing it.
     */
    private suspend fun outcome(call: suspend () -> Unit): String =
        withTimeoutOrNull(PENDING_TIMEOUT_MS) {
            try {
                call()
                "returned without an error"
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
        } ?: "still pending after ${PENDING_TIMEOUT_MS / 1000} seconds"

    @Test
    fun callsAfterReaderFailure_throwTheErrorThatEndedIt() = runBlocking {
        // Arrange: a worklet whose first reply is a frame of length zero, which ends the reader.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wdk = WdkCore(context, "zero-length-reply.bundle")

        try {
            // Act: the first call is in flight when the reader ends, the second is made afterwards.
            val first = outcome { wdk.workletStart() }
            val second = outcome { wdk.callMethod(methodName = "ping", network = "none") }

            // Assert
            assertEquals("IPC Error: Invalid message length: 0", first)
            assertEquals("IPC Error: Invalid message length: 0", second)
        } finally {
            wdk.close()
        }
    }

    @Test
    fun afterReaderFailure_closeThenNewInstanceWorks() = runBlocking {
        // Arrange: an instance whose reader has ended, closed as an app would close it.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val failed = WdkCore(context, "zero-length-reply.bundle")
        outcome { failed.workletStart() }
        failed.close()

        // Act: a fresh instance in the same process, on the real bundle, through a full derivation.
        val wdk = WdkCore(context)
        try {
            wdk.workletStart()
            val secrets = wdk.getSeedAndEntropyFromMnemonic(TEST_MNEMONIC)
            wdk.initializeWDK(
                encryptionKey = secrets.encryptionKey,
                encryptedSeed = secrets.encryptedSeedBuffer,
                config = ETHEREUM_CONFIG
            )
            val address = wdk.getAddress("ethereum")

            // Assert: the first Ethereum account of the BIP39 test mnemonic (m/44'/60'/0'/0/0).
            assertEquals("0x9858EfFD232B4033E47d90003D41EC34EcaEda94", address)
        } finally {
            wdk.dispose()
            wdk.close()
        }
    }

    private companion object {
        const val PENDING_TIMEOUT_MS = 5_000L
        const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val ETHEREUM_CONFIG = """
            {
                "networks": {
                    "ethereum": {
                        "blockchain": "ethereum",
                        "config": { "rpcUrl": "https://eth.example.com" }
                    }
                }
            }
        """.trimIndent()
    }
}
