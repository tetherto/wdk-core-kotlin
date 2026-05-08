package to.tether.wdk.core

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class WdkCoreTest {

    companion object {
        private lateinit var wdkCore: WdkCore

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            wdkCore = WdkCore(context)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            wdkCore.close()
        }
    }

    @Test
    fun test01_construction() {
        assertNotNull(wdkCore)
    }

    @Test
    fun test02_workletStart() = runBlocking {
        wdkCore.workletStart()
    }

    @Test
    fun test03_generateEntropyAndEncrypt12Words() = runBlocking {
        val result = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        assertTrue(result.encryptionKey.isNotEmpty())
        assertTrue(result.encryptedSeedBuffer.isNotEmpty())
        assertTrue(result.encryptedEntropyBuffer.isNotEmpty())
    }

    @Test
    fun test04_generateEntropyAndEncrypt24Words() = runBlocking {
        val result = wdkCore.generateEntropyAndEncrypt(wordCount = 24)

        assertTrue(result.encryptionKey.isNotEmpty())
        assertTrue(result.encryptedSeedBuffer.isNotEmpty())
        assertTrue(result.encryptedEntropyBuffer.isNotEmpty())
    }

    @Test
    fun test05_roundTripEntropyToMnemonic() = runBlocking {
        // Generate entropy
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        // Decrypt to mnemonic
        val mnemonic = wdkCore.getMnemonicFromEntropy(
            encryptedEntropy = entropy.encryptedEntropyBuffer,
            encryptionKey = entropy.encryptionKey
        )

        // Verify 12 words
        val words = mnemonic.trim().split(" ")
        assertEquals(12, words.size)
        words.forEach { assertTrue(it.isNotEmpty()) }
    }

    @Test
    fun test06_getSeedAndEntropyFromMnemonic() = runBlocking {
        // First generate entropy and get a mnemonic
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)
        val mnemonic = wdkCore.getMnemonicFromEntropy(
            encryptedEntropy = entropy.encryptedEntropyBuffer,
            encryptionKey = entropy.encryptionKey
        )

        // Convert mnemonic back to seed+entropy
        val result = wdkCore.getSeedAndEntropyFromMnemonic(mnemonic)

        assertTrue(result.encryptionKey.isNotEmpty())
        assertTrue(result.encryptedSeedBuffer.isNotEmpty())
        assertTrue(result.encryptedEntropyBuffer.isNotEmpty())
    }

    @Test
    fun test07_initializeWDK_matchingConfig() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        // Network key must match the blockchain field (new pear-wrk-wdk requirement)
        val config = """
            {
                "networks": {
                    "ethereum": {
                        "blockchain": "ethereum",
                        "config": { "rpcUrl": "https://eth.example.com" }
                    }
                }
            }
        """.trimIndent()

        wdkCore.initializeWDK(
            encryptionKey = entropy.encryptionKey,
            encryptedSeed = entropy.encryptedSeedBuffer,
            config = config
        )
    }

    @Test
    fun test08_initializeWDK_mismatchedConfigFails() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        // "sepolia" key with blockchain "ethereum" should fail
        val config = """
            {
                "networks": {
                    "sepolia": {
                        "blockchain": "ethereum",
                        "config": { "rpcUrl": "https://sepolia.example.com" }
                    }
                }
            }
        """.trimIndent()

        try {
            wdkCore.initializeWDK(
                encryptionKey = entropy.encryptionKey,
                encryptedSeed = entropy.encryptedSeedBuffer,
                config = config
            )
            fail("Expected WdkError.RpcError for mismatched network key/blockchain")
        } catch (e: WdkError.RpcError) {
            assertTrue(
                "Error should mention 'must match blockchain', got: ${e.message}",
                e.message.contains("must match blockchain")
            )
        }
    }

    @Test
    fun test09_initializeWDK_multipleNetworks() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        val config = """
            {
                "networks": {
                    "ethereum": {
                        "blockchain": "ethereum",
                        "config": { "rpcUrl": "https://eth.example.com" }
                    },
                    "sepolia": {
                        "blockchain": "sepolia",
                        "config": { "rpcUrl": "https://sepolia.example.com" }
                    }
                }
            }
        """.trimIndent()

        wdkCore.initializeWDK(
            encryptionKey = entropy.encryptionKey,
            encryptedSeed = entropy.encryptedSeedBuffer,
            config = config
        )
    }

    @Test
    fun test10_initializeWDK_bitcoinMatchingConfig() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        val config = """
            {
                "networks": {
                    "bitcoin": {
                        "blockchain": "bitcoin",
                        "config": {}
                    }
                }
            }
        """.trimIndent()

        wdkCore.initializeWDK(
            encryptionKey = entropy.encryptionKey,
            encryptedSeed = entropy.encryptedSeedBuffer,
            config = config
        )
    }

    @Test
    fun test11_initializeWDK_bitcoinMismatchedConfigFails() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        val config = """
            {
                "networks": {
                    "btc-testnet": {
                        "blockchain": "bitcoin",
                        "config": {}
                    }
                }
            }
        """.trimIndent()

        try {
            wdkCore.initializeWDK(
                encryptionKey = entropy.encryptionKey,
                encryptedSeed = entropy.encryptedSeedBuffer,
                config = config
            )
            fail("Expected WdkError.RpcError for mismatched bitcoin network key/blockchain")
        } catch (e: WdkError.RpcError) {
            assertTrue(
                "Error should mention 'must match blockchain', got: ${e.message}",
                e.message.contains("must match blockchain")
            )
        }
    }

    @Test
    fun test12_initializeWDK_mixedEvmAndBitcoin() = runBlocking {
        val entropy = wdkCore.generateEntropyAndEncrypt(wordCount = 12)

        val config = """
            {
                "networks": {
                    "sepolia": {
                        "blockchain": "sepolia",
                        "config": { "rpcUrl": "https://sepolia.example.com" }
                    },
                    "bitcoin": {
                        "blockchain": "bitcoin",
                        "config": {}
                    }
                }
            }
        """.trimIndent()

        wdkCore.initializeWDK(
            encryptionKey = entropy.encryptionKey,
            encryptedSeed = entropy.encryptedSeedBuffer,
            config = config
        )
    }

    @Test
    fun test13_registerWallet_matchingConfig() = runBlocking {
        val config = """
            {
                "polygon": {
                    "blockchain": "polygon",
                    "config": { "rpcUrl": "https://polygon.example.com" }
                }
            }
        """.trimIndent()

        val registered = wdkCore.registerWallet(config)
        assertTrue("Should register polygon", registered.contains("polygon"))
    }

    @Test
    fun test14_registerWallet_bitcoinMatchingConfig() = runBlocking {
        val config = """
            {
                "bitcoin": {
                    "blockchain": "bitcoin",
                    "config": {}
                }
            }
        """.trimIndent()

        val registered = wdkCore.registerWallet(config)
        assertTrue("Should register bitcoin", registered.contains("bitcoin"))
    }

    @Test
    fun test15_registerWallet_mismatchedConfigFails() = runBlocking {
        val config = """
            {
                "mainnet": {
                    "blockchain": "ethereum",
                    "config": { "rpcUrl": "https://eth.example.com" }
                }
            }
        """.trimIndent()

        try {
            wdkCore.registerWallet(config)
            fail("Expected WdkError.RpcError for mismatched network key/blockchain")
        } catch (e: WdkError.RpcError) {
            assertTrue(
                "Error should mention 'must match blockchain', got: ${e.message}",
                e.message.contains("must match blockchain")
            )
        }
    }

    @Test
    fun test16_rpcErrorHandling() = runBlocking {
        try {
            wdkCore.generateEntropyAndEncrypt(wordCount = 7)
            fail("Expected WdkError.RpcError")
        } catch (e: WdkError.RpcError) {
            assertTrue(e.message.isNotEmpty())
        }
    }

    @Test
    fun test17_dispose() = runBlocking {
        wdkCore.dispose()
    }
}
