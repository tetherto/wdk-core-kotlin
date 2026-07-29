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

    /**
     * Regression test for the BoringSSL pthread_key dangling-destructor crash.
     *
     * Before bare-crypto 1.13.7 / bare-tls 3.1.4, calling worklet.terminate() inside
     * close() would let V8 unload libbare-crypto.so / libbare-tls.so. BoringSSL's
     * pthread_key destructor (registered via pthread_key_create) then pointed into
     * unmapped memory, and the next thread to exit anywhere in the process crashed
     * inside pthread_key_clean_all with SIGSEGV.
     *
     * This test reproduces that scenario by spawning fresh threads *after* close()
     * and joining them — forcing the kernel to walk pthread keys for those threads.
     * If the destructor pointer is still valid (i.e. the addon stays mapped), the
     * threads exit cleanly. If it's dangling, the JVM dies and this test fails to
     * complete.
     */
    @Test
    fun threadExitAfterClose_doesNotCrashFromDanglingDestructor() = runBlocking {
        val wdk = createWdkCore()

        wdk.workletStart()
        wdk.generateEntropyAndEncrypt(wordCount = 12)
        wdk.dispose()
        wdk.close()

        repeat(20) {
            Thread {
                Thread.sleep(5)
            }.apply {
                start()
                join(1000)
            }
        }
    }
}
