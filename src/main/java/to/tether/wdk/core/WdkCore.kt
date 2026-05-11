package to.tether.wdk.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WdkCore(private val context: Context) : Closeable {
    private val worklet = Worklet(null)
    private var ipc: IPC? = null
    private val requestId = AtomicInteger(0)
    private val ipcMutex = Mutex()
    private var readBuffer = ByteArray(0)
    private var isWorkletStarted = false
    private val closed = AtomicBoolean(false)

    // IPC must be created and used on a thread with an Android Looper
    private var ipcThread: HandlerThread? = null
    private var ipcHandler: Handler? = null

    // Queue for incoming IPC data chunks
    private val incomingData = LinkedBlockingQueue<ByteArray>()

    private suspend fun ensureWorkletStarted() {
        if (isWorkletStarted) return

        // Start the HandlerThread for IPC
        val thread = HandlerThread("WdkIPC").apply { start() }
        ipcThread = thread
        ipcHandler = Handler(thread.looper)

        withContext(Dispatchers.IO) {
            val inputStream = context.assets.open("wdk.bundle")
            worklet.start("/wdk.bundle", inputStream, null)
        }

        // Give worklet time to initialize
        delay(500)

        // Create IPC on the HandlerThread which has an ALooper
        val latch = CountDownLatch(1)
        ipcHandler!!.post {
            ipc = IPC(worklet)
            // Start continuous reading via callbacks
            startReading()
            latch.countDown()
        }
        withContext(Dispatchers.IO) {
            latch.await()
        }

        isWorkletStarted = true
    }

    private fun startReading() {
        if (closed.get()) return
        val ipc = this.ipc ?: return
        ipc.read { data, _ ->
            if (data != null && !closed.get()) {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                incomingData.put(bytes)
                // Continue reading
                startReading()
            }
        }
    }

    // -- Framing methods --

    private suspend fun writeFramed(data: ByteArray) {
        val ipc = this.ipc ?: throw WdkError.IpcError("IPC not initialized")
        val handler = this.ipcHandler ?: throw WdkError.IpcError("IPC not initialized")

        val frame = ByteBuffer.allocate(4 + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size)
            .put(data)
        frame.flip()

        val latch = CountDownLatch(1)
        handler.post {
            ipc.write(frame)
            latch.countDown()
        }
        withContext(Dispatchers.IO) {
            latch.await()
        }
    }

    private suspend fun readFramed(): ByteArray {
        // Read 4-byte length header
        val lengthData = readExactly(4)
        val length = ByteBuffer.wrap(lengthData)
            .order(ByteOrder.BIG_ENDIAN)
            .int

        // Validate message size (max 10MB)
        if (length !in 1..9_999_999) {
            throw WdkError.IpcError("Invalid message length: $length")
        }

        return readExactly(length)
    }

    private suspend fun readExactly(bytes: Int): ByteArray {
        while (readBuffer.size < bytes) {
            if (closed.get()) throw WdkError.IpcError("Connection closed")
            val chunk = withContext(Dispatchers.IO) {
                // Poll with timeout so we can check the closed flag
                var data: ByteArray? = null
                while (data == null && !closed.get()) {
                    data = incomingData.poll(100, TimeUnit.MILLISECONDS)
                }
                data ?: throw WdkError.IpcError("Connection closed while reading")
            }
            readBuffer += chunk
        }

        val result = readBuffer.copyOfRange(0, bytes)
        readBuffer = readBuffer.copyOfRange(bytes, readBuffer.size)
        return result
    }

    // -- JSON-RPC --

    @Suppress("SpellCheckingInspection")
    private suspend fun call(method: String, params: JSONObject): JSONObject {
        ensureWorkletStarted()

        val id = requestId.incrementAndGet()

        val request = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }

        val requestData = request.toString().toByteArray(Charsets.UTF_8)

        ipcMutex.withLock {
            writeFramed(requestData)
            val responseData = readFramed()

            val responseStr = String(responseData, Charsets.UTF_8)
            val response = JSONObject(responseStr)

            if (response.has("error")) {
                val error = response.getJSONObject("error")
                val errorMessage = error.optString("message", "Unknown error")
                val errorCode = error.optString("code", "UNKNOWN")
                throw WdkError.RpcError(code = errorCode, message = errorMessage)
            }

            if (!response.has("result")) {
                throw WdkError.InvalidResponse("Missing result in response")
            }

            return response.getJSONObject("result")
        }
    }

    // -- Public API --
    // Functions are suppressed for 'unused' because they are the public API for the library consumer.

    @Suppress("unused")
    suspend fun workletStart() {
        call("workletStart", JSONObject())
    }

    @Suppress("unused")
    suspend fun generateEntropyAndEncrypt(wordCount: Int): EntropyResult {
        val result = call("generateEntropyAndEncrypt", JSONObject().apply {
            put("wordCount", wordCount)
        })

        return EntropyResult(
            encryptionKey = result.optString("encryptionKey")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid generateEntropyAndEncrypt response"),
            encryptedSeedBuffer = result.optString("encryptedSeedBuffer")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid generateEntropyAndEncrypt response"),
            encryptedEntropyBuffer = result.optString("encryptedEntropyBuffer")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid generateEntropyAndEncrypt response")
        )
    }

    @Suppress("unused")
    suspend fun getMnemonicFromEntropy(encryptedEntropy: String, encryptionKey: String): String {
        val result = call("getMnemonicFromEntropy", JSONObject().apply {
            put("encryptedEntropy", encryptedEntropy)
            put("encryptionKey", encryptionKey)
        })

        return result.optString("mnemonic")
            .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid getMnemonicFromEntropy response")
    }

    @Suppress("unused")
    suspend fun getSeedAndEntropyFromMnemonic(mnemonic: String): SeedAndEntropyResult {
        val result = call("getSeedAndEntropyFromMnemonic", JSONObject().apply {
            put("mnemonic", mnemonic)
        })

        return SeedAndEntropyResult(
            encryptionKey = result.optString("encryptionKey")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid getSeedAndEntropyFromMnemonic response"),
            encryptedSeedBuffer = result.optString("encryptedSeedBuffer")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid getSeedAndEntropyFromMnemonic response"),
            encryptedEntropyBuffer = result.optString("encryptedEntropyBuffer")
                .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid getSeedAndEntropyFromMnemonic response")
        )
    }

    @Suppress("unused")
    suspend fun initializeWDK(encryptionKey: String, encryptedSeed: String, config: String) {
        call("initializeWDK", JSONObject().apply {
            put("encryptionKey", encryptionKey)
            put("encryptedSeed", encryptedSeed)
            put("config", config)
        })
    }

    @Suppress("unused")
    suspend fun callMethod(
        methodName: String,
        network: String,
        accountIndex: Int = 0,
        args: String? = null,
        options: String? = null
    ): Any {
        val params = JSONObject().apply {
            put("methodName", methodName)
            put("network", network)
            put("accountIndex", accountIndex)
            if (args != null) put("args", args)
            if (options != null) put("options", options)
        }

        val result = call("callMethod", params)

        return result.opt("result")
            ?: throw WdkError.InvalidResponse("Invalid callMethod response")
    }

    @Suppress("unused")
    suspend fun getAddress(network: String, accountIndex: Int = 0): String {
        val result = callMethod(
            methodName = "getAddress",
            network = network,
            accountIndex = accountIndex
        )

        return result as? String
            ?: throw WdkError.InvalidResponse("Invalid address format")
    }

    @Suppress("unused")
    suspend fun getBalance(network: String, accountIndex: Int = 0): String {
        val result = callMethod(
            methodName = "getBalance",
            network = network,
            accountIndex = accountIndex
        )

        return result as? String
            ?: throw WdkError.InvalidResponse("Invalid balance format")
    }

    @Suppress("unused")
    suspend fun registerWallet(config: String): List<String> {
        val result = call("registerWallet", JSONObject().apply {
            put("config", config)
        })

        val blockchainsString = result.optString("blockchains")
            .takeIf { it.isNotEmpty() } ?: throw WdkError.InvalidResponse("Invalid registerWallet response")

        val blockchainsArray = org.json.JSONArray(blockchainsString)
        return (0 until blockchainsArray.length()).map { blockchainsArray.getString(it) }
    }

    @Suppress("unused")
    suspend fun registerProtocol(config: String) {
        call("registerProtocol", JSONObject().apply {
            put("config", config)
        })
    }

    @Suppress("unused")
    suspend fun dispose() {
        call("dispose", JSONObject())
    }

    // -- Lifecycle --

    override fun close() {
        if (closed.getAndSet(true)) return

        ipcHandler = null

        ipc?.close()
        ipc = null

        val thread = ipcThread
        ipcThread = null
        thread?.quitSafely()
        thread?.join()

        isWorkletStarted = false
        readBuffer = ByteArray(0)
        incomingData.clear()
    }

    @Suppress("unused")
    fun suspend() {
        worklet.suspend()
    }

    @Suppress("unused")
    fun resume() {
        worklet.resume()
    }
}
