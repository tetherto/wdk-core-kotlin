package to.tether.wdk.core

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Validates that WdkCore.close() handles teardown safely without SIGSEGV.
 * Each test creates and tears down its own WdkCore instance.
 */
@RunWith(AndroidJUnit4::class)
class WdkCoreTeardownTest {

    private fun createWdkCore(): WdkCore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return WdkCore(context)
    }

    @Test
    fun disposeAndClose_doesNotCrash() = runBlocking {
        val wdk = createWdkCore()

        wdk.workletStart()
        val entropy = wdk.generateEntropyAndEncrypt(wordCount = 12)
        assertNotNull(entropy)

        wdk.dispose()
        wdk.close()
    }

    @Test
    fun closeWithoutDispose_doesNotCrash() = runBlocking {
        val wdk = createWdkCore()

        wdk.workletStart()
        wdk.generateEntropyAndEncrypt(wordCount = 12)

        wdk.close()
    }

    @Test
    fun doubleClose_isIdempotent() = runBlocking {
        val wdk = createWdkCore()

        wdk.workletStart()
        wdk.dispose()
        wdk.close()
        wdk.close()
    }

    @Test
    fun closeBeforeAnyCall_doesNotCrash() {
        val wdk = createWdkCore()
        wdk.close()
    }
}
