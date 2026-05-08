package to.tether.wdk.core

sealed class WdkError(message: String) : Exception(message) {
    class IpcError(message: String) : WdkError("IPC Error: $message")
    class RpcError(val code: String, override val message: String) : WdkError("RPC Error [$code]: $message")
    class InvalidResponse(message: String) : WdkError("Invalid Response: $message")
}
