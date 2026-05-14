package to.tether.wdk.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import to.holepunch.bare.kit.IPC
import to.holepunch.bare.kit.Worklet
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WdkCore(private val context: Context) : Closeable {
    private val worklet = Worklet(null)
    private var ipc: IPC? = null
    private val requestId = AtomicInteger(0)
    private var isWorkletStarted = false
    private val closed = AtomicBoolean(false)

    private var ipcThread: HandlerThread? = null
    private var ipcHandler: Handler? = null

    // ID-based multiplexing: each in-flight request has a CompletableDeferred keyed by its JSON-RPC id.
    // The reader coroutine resolves the correct deferred when a response arrives.
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()

    private var readerJob: Job? = null
    private val readerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Only protects IPC writes — reads are dispatched by the single reader coroutine
    private val writeMutex = Mutex()

    private val incomingData = LinkedBlockingQueue<ByteArray>()

    private suspend fun ensureWorkletStarted() {
        if (isWorkletStarted) return

        val thread = HandlerThread("WdkIPC").apply { start() }
        ipcThread = thread
        ipcHandler = Handler(thread.looper)

        withContext(Dispatchers.IO) {
            val inputStream = context.assets.open("wdk.bundle")
            worklet.start("/wdk.bundle", inputStream, null)
        }

        delay(500)

        val latch = CountDownLatch(1)
        ipcHandler!!.post {
            ipc = IPC(worklet)
            startReading()
            latch.countDown()
        }
        withContext(Dispatchers.IO) {
            latch.await()
        }

        isWorkletStarted = true
        startReaderLoop()
    }

    /**
     * ALooper-based continuous reading that feeds raw chunks into [incomingData].
     * Must be called on the IPC handler thread.
     */
    private fun startReading() {
        if (closed.get()) return
        val ipc = this.ipc ?: return
        ipc.read { data, _ ->
            if (data != null && !closed.get()) {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                incomingData.put(bytes)
                startReading()
            }
        }
    }

    // -- Reader loop (single coroutine that owns all read-side state) --

    private fun startReaderLoop() {
        readerJob = readerScope.launch {
            var readBuffer = ByteArray(0)
            try {
                while (isActive && !closed.get()) {
                    readBuffer = accumulate(readBuffer, 4)

                    val length = ByteBuffer.wrap(readBuffer, 0, 4)
                        .order(ByteOrder.BIG_ENDIAN).int

                    if (length !in 1..9_999_999) break

                    val frameEnd = 4 + length
                    readBuffer = accumulate(readBuffer, frameEnd)

                    val messageBytes = readBuffer.copyOfRange(4, frameEnd)
                    readBuffer = readBuffer.copyOfRange(frameEnd, readBuffer.size)

                    try {
                        val response = JSONObject(String(messageBytes, Charsets.UTF_8))
                        val id = response.optInt("id", -1)
                        if (id > 0) {
                            pendingRequests.remove(id)?.complete(response)
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: CancellationException) {
            } finally {
                rejectAllPending()
            }
        }
    }

    /** Block until [readBuffer] has at least [needed] bytes, polling [incomingData]. */
    private suspend fun accumulate(buffer: ByteArray, needed: Int): ByteArray {
        var buf = buffer
        while (buf.size < needed) {
            if (closed.get()) throw CancellationException("closed")
            val chunk = withContext(Dispatchers.IO) {
                var data: ByteArray? = null
                while (data == null && !closed.get()) {
                    data = incomingData.poll(100, TimeUnit.MILLISECONDS)
                }
                data ?: throw CancellationException("closed")
            }
            buf += chunk
        }
        return buf
    }

    private fun rejectAllPending() {
        val error = WdkError.IpcError("Connection closed")
        val iter = pendingRequests.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            iter.remove()
            entry.value.completeExceptionally(error)
        }
    }

    // -- Framing: write --

    private suspend fun writeFramed(data: ByteArray) {
        val ipc = this.ipc ?: throw WdkError.IpcError("IPC not initialized")
        val handler = this.ipcHandler ?: throw WdkError.IpcError("IPC not initialized")

        val frame = ByteBuffer.allocate(4 + data.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(data.size)
            .put(data)
        frame.flip()

        writeMutex.withLock {
            val latch = CountDownLatch(1)
            handler.post {
                ipc.write(frame)
                latch.countDown()
            }
            withContext(Dispatchers.IO) {
                latch.await()
            }
        }
    }

    // -- JSON-RPC with ID-based multiplexing --

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

        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        try {
            writeFramed(requestData)
        } catch (e: Exception) {
            pendingRequests.remove(id)
            throw e
        }

        val response = deferred.await()

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

    // -- Public API --

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
    //
    // Teardown order:
    //   1. Stop the ALooper readable poll  (no more read callbacks)
    //   2. Cancel reader coroutine & reject all pending deferreds
    //   3. Quit handler thread             (ALooper exits)
    //   4. ipc.close()                     (bare_ipc_destroy — close dup'd FDs)
    //   5. worklet.terminate()             (V8 teardown — safe with bare-crypto >= 1.13.7
    //                                       and bare-tls >= 3.1.4 which pin themselves via
    //                                       RTLD_NODELETE so pthread_key destructors stay
    //                                       valid even after V8 unloads its module refs)

    override fun close() {
        if (closed.getAndSet(true)) return

        val handler = ipcHandler
        val localIpc = ipc
        ipcHandler = null

        // 1. Unregister ALooper readable poll on the handler thread
        if (handler != null && localIpc != null) {
            try {
                val latch = CountDownLatch(1)
                handler.post {
                    try { localIpc.readable(null) } catch (_: Exception) {}
                    latch.countDown()
                }
                latch.await(1, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        // 2. Cancel reader & reject pending
        readerJob?.cancel()
        readerScope.cancel()
        rejectAllPending()
        incomingData.clear()

        // 3. Stop handler thread (quits the ALooper, prevents further native callbacks)
        val thread = ipcThread
        ipcThread = null
        thread?.quitSafely()
        thread?.join(2000)

        // 4. Destroy IPC (closes dup'd file descriptors)
        try { localIpc?.close() } catch (_: Exception) {}
        ipc = null

        if (isWorkletStarted) {
            try { worklet.terminate() } catch (_: Exception) {}
        }
        isWorkletStarted = false
    }

    @Suppress("unused")
    fun suspend() {
        if (closed.get()) return
        worklet.suspend()
    }

    @Suppress("unused")
    fun resume() {
        if (closed.get()) return
        worklet.resume()
    }
}
