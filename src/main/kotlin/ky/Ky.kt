package com.zhuxietong.ky

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// 请求/响应数据类
data class KyRequest(
    val url: String,
    val method: String = "GET",
    val headers: MutableMap<String, String> = mutableMapOf(),
    val body: Any? = null,
    val timeout: Long = 30000,
    val retries: Int = 0
)

data class KyResponse(
    val status: Int,
    val statusText: String,
    val headers: Headers,
    val body: String,
    val url: String,
    val ok: Boolean = status in 200..299
) {
    suspend inline fun <reified T> json(): T {
        return Json.decodeFromString<T>(body)
    }

    fun text(): String = body
}

// Hook 接口定义
interface KyHook {
    suspend fun beforeRequest(request: KyRequest): KyRequest = request
    suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse = response
    suspend fun beforeRetry(request: KyRequest, error: Throwable, retryCount: Int): KyRequest = request
    suspend fun onError(request: KyRequest, error: Throwable): Throwable = error
}

// 异常类
class KyException(
    val request: KyRequest,
    val response: KyResponse? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// 主要的 Ky 类
class Ky private constructor(
    private val client: OkHttpClient,
    private val baseUrl: String = "",
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val hooks: List<KyHook> = emptyList(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    companion object {
        fun create(
            baseUrl: String = "",
            timeout: Long = 30000,
            headers: Map<String, String> = emptyMap(),
            hooks: List<KyHook> = emptyList()
        ): Ky {
            val client = OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()

            return Ky(
                client = client,
                baseUrl = baseUrl,
                defaultHeaders = headers,
                hooks = hooks
            )
        }
    }

    // 扩展方法，创建新的实例
    fun extend(
        baseUrl: String = this.baseUrl,
        headers: Map<String, String> = this.defaultHeaders,
        hooks: List<KyHook> = this.hooks,
        timeout: Long? = null
    ): Ky {
        val newClient = if (timeout != null) {
            client.newBuilder()
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
        } else {
            client
        }

        return Ky(
            client = newClient,
            baseUrl = baseUrl,
            defaultHeaders = headers,
            hooks = hooks,
            json = json
        )
    }

    // 主要请求方法
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: Any? = null,
        timeout: Long? = null,
        retries: Int = 0
    ): KyResponse = withContext(Dispatchers.IO) {
        val fullUrl = if (url.startsWith("http")) url else "$baseUrl$url"
        val mergedHeaders = defaultHeaders + headers

        var request = KyRequest(
            url = fullUrl,
            method = method.uppercase(),
            headers = mergedHeaders.toMutableMap(),
            body = body,
            timeout = timeout ?: 30000,
            retries = retries
        )

        // 应用 beforeRequest hooks
        for (hook in hooks) {
            request = hook.beforeRequest(request)
        }

        var lastException: Throwable? = null

        repeat(request.retries + 1) { attempt ->
            try {
                if (attempt > 0) {
                    // 应用 beforeRetry hooks
                    for (hook in hooks) {
                        request = hook.beforeRetry(request, lastException!!, attempt)
                    }
                }

                val response = executeRequest(request)

                // 应用 afterResponse hooks
                var finalResponse = response
                for (hook in hooks) {
                    finalResponse = hook.afterResponse(request, finalResponse)
                }

                if (!finalResponse.ok) {
                    throw KyException(
                        request = request,
                        response = finalResponse,
                        message = "HTTP ${finalResponse.status}: ${finalResponse.statusText}"
                    )
                }

                return@withContext finalResponse

            } catch (e: Exception) {
                lastException = e

                // 应用 onError hooks
                var processedException = e
                for (hook in hooks) {
                    processedException = hook.onError(request, processedException) as Exception
                }

                if (attempt == request.retries) {
                    throw processedException
                }

                // 重试延迟
                delay(1000L * (attempt + 1))
            }
        }

        throw lastException!!
    }

    private suspend fun executeRequest(request: KyRequest): KyResponse {
        val requestBuilder = Request.Builder()
            .url(request.url)

        // 添加请求头
        request.headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        // 处理请求体
        when {
            request.body != null && request.method in listOf("POST", "PUT", "PATCH") -> {
                val requestBody = when (request.body) {
                    is String -> request.body.toRequestBody("text/plain".toMediaType())
                    is ByteArray -> request.body.toRequestBody("application/octet-stream".toMediaType())
                    else -> {
                        // 序列化为 JSON
                        val jsonString = json.encodeToString(request.body)
                        jsonString.toRequestBody("application/json".toMediaType())
                    }
                }
                requestBuilder.method(request.method, requestBody)
            }
            request.method == "GET" -> requestBuilder.get()
            request.method == "DELETE" -> requestBuilder.delete()
            request.method == "HEAD" -> requestBuilder.head()
            else -> requestBuilder.method(request.method, null)
        }

        val okHttpRequest = requestBuilder.build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(okHttpRequest)

            continuation.invokeOnCancellation {
                call.cancel()
            }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.body?.string() ?: ""
                        val kyResponse = KyResponse(
                            status = response.code,
                            statusText = response.message,
                            headers = response.headers,
                            body = body,
                            url = response.request.url.toString()
                        )
                        continuation.resumeWith(Result.success(kyResponse))
                    } catch (e: Exception) {
                        continuation.resumeWith(Result.failure(e))
                    } finally {
                        response.close()
                    }
                }
            })
        }
    }

    // 便捷方法
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null
    ) = request(url, "GET", headers, timeout = timeout)

    suspend fun post(
        url: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null
    ) = request(url, "POST", headers, body, timeout)

    suspend fun put(
        url: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null
    ) = request(url, "PUT", headers, body, timeout)

    suspend fun delete(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null
    ) = request(url, "DELETE", headers, timeout = timeout)

    suspend fun patch(
        url: String,
        body: Any? = null,
        headers: Map<String, String> = emptyMap(),
        timeout: Long? = null
    ) = request(url, "PATCH", headers, body, timeout)
}

// 示例 Hook 实现
// 增强的日志 Hook 实现
class LoggingHook(
    private val logLevel: LogLevel = LogLevel.INFO,
    private val logRequestHeaders: Boolean = false,
    private val logResponseHeaders: Boolean = false,
    private val maxBodyLength: Int = 1000,
    private val prettyPrintJson: Boolean = true,
    private val logRequestBody: Boolean = true,
    private val logResponseBody: Boolean = true
) : KyHook {

    enum class LogLevel {
        NONE, ERROR, WARN, INFO, DEBUG, VERBOSE
    }

    private val json = Json {
        prettyPrint = prettyPrintJson
        ignoreUnknownKeys = true
    }

    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            val arrow = "🚀"
            val method = request.method.padEnd(6)
            println("$arrow [$method] ${request.url}")

            // 打印请求头
            if (logRequestHeaders && request.headers.isNotEmpty() && logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
                println("   📋 Request Headers:")
                request.headers.forEach { (key, value) ->
                    // 隐藏敏感信息
                    val displayValue = if (key.lowercase().contains("authorization") ||
                        key.lowercase().contains("token") ||
                        key.lowercase().contains("key")) {
                        "***${value.takeLast(4)}"
                    } else {
                        value
                    }
                    println("      $key: $displayValue")
                }
            }

            // 打印请求体
            if (logRequestBody && request.body != null && logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
                println("   📤 Request Body:")
                printBody(request.body, "      ")
            }
        }
        return request
    }

    override suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse {
        if (logLevel.ordinal >= LogLevel.INFO.ordinal) {
            val statusIcon = when {
                response.status in 200..299 -> "✅"
                response.status in 300..399 -> "🔄"
                response.status in 400..499 -> "⚠️"
                response.status >= 500 -> "❌"
                else -> "❓"
            }

            val method = request.method.padEnd(6)
            val statusText = if (response.statusText.isNotEmpty()) " ${response.statusText}" else ""

            println("$statusIcon [$method] ${response.status}$statusText ${request.url}")

            // 打印响应头
            if (logResponseHeaders && logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
                println("   📋 Response Headers:")
                response.headers.forEach { (name, value) ->
                    println("      $name: $value")
                }
            }

            // 打印响应体
            if (logResponseBody && response.body.isNotEmpty() && logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
                println("   📥 Response Body:")
                printResponseBody(response.body, "      ")
            }

            // 在 VERBOSE 模式下打印更多信息
            if (logLevel == LogLevel.VERBOSE) {
                println("   ⏱️  Response Time: ${System.currentTimeMillis()} ms") // 这里需要在实际使用时计算真实时间
                println("   📊 Content-Length: ${response.body.length} bytes")
            }
        }
        return response
    }

    override suspend fun beforeRetry(request: KyRequest, error: Throwable, retryCount: Int): KyRequest {
        if (logLevel.ordinal >= LogLevel.WARN.ordinal) {
            val method = request.method.padEnd(6)
            println("🔄 [$method] Retry #$retryCount ${request.url}")
            println("   💭 Reason: ${error.message}")
        }
        return request
    }

    override suspend fun onError(request: KyRequest, error: Throwable): Throwable {
        if (logLevel.ordinal >= LogLevel.ERROR.ordinal) {
            val method = request.method.padEnd(6)
            println("❌ [$method] Error ${request.url}")
            println("   💥 ${error.javaClass.simpleName}: ${error.message}")

            if (logLevel.ordinal >= LogLevel.DEBUG.ordinal) {
                error.printStackTrace()
            }
        }
        return error
    }

    private fun printBody(body: Any?, prefix: String) {
        when (body) {
            is String -> {
                if (isValidJson(body)) {
                    printJsonString(body, prefix)
                } else {
                    printTruncatedString(body, prefix)
                }
            }
            is ByteArray -> {
                println("${prefix}[Binary Data: ${body.size} bytes]")
            }
            else -> {
                try {
                    val jsonString = json.encodeToString(body)
                    printJsonString(jsonString, prefix)
                } catch (e: Exception) {
                    println("${prefix}${body.toString().take(maxBodyLength)}")
                }
            }
        }
    }

    private fun printResponseBody(body: String, prefix: String) {
        when {
            body.isEmpty() -> println("${prefix}[Empty Response]")
            isValidJson(body) -> printJsonString(body, prefix)
            body.startsWith("<!DOCTYPE html") || body.startsWith("<html") -> {
                println("${prefix}[HTML Content: ${body.length} chars]")
                if (logLevel == LogLevel.VERBOSE) {
                    println("${prefix}${body.take(200)}...")
                }
            }
            body.startsWith("<?xml") -> {
                println("${prefix}[XML Content: ${body.length} chars]")
                if (logLevel == LogLevel.VERBOSE) {
                    println("${prefix}${body.take(200)}...")
                }
            }
            else -> printTruncatedString(body, prefix)
        }
    }

    private fun printJsonString(jsonString: String, prefix: String) {
        try {
            if (prettyPrintJson && jsonString.length <= maxBodyLength) {
                // 格式化 JSON
                val jsonElement = Json.parseToJsonElement(jsonString)
                val prettyJson = json.encodeToString(jsonElement)
                prettyJson.lines().forEach { line ->
                    println("$prefix$line")
                }
            } else {
                // 压缩显示或截断
                val compactJson = jsonString.replace(Regex("\\s+"), " ")
                printTruncatedString(compactJson, prefix)
            }
        } catch (e: Exception) {
            printTruncatedString(jsonString, prefix)
        }
    }

    private fun printTruncatedString(text: String, prefix: String) {
        if (text.length <= maxBodyLength) {
            text.lines().forEach { line ->
                println("$prefix$line")
            }
        } else {
            val truncated = text.take(maxBodyLength)
            truncated.lines().forEach { line ->
                println("$prefix$line")
            }
            println("$prefix... [truncated ${text.length - maxBodyLength} more chars]")
        }
    }

    private fun isValidJson(text: String): Boolean {
        return try {
            Json.parseToJsonElement(text.trim())
            true
        } catch (e: Exception) {
            false
        }
    }
}

// 便捷的预设配置
object LoggingPresets {
    fun minimal() = LoggingHook(
        logLevel = LoggingHook.LogLevel.INFO,
        logRequestHeaders = false,
        logResponseHeaders = false,
        logRequestBody = false,
        logResponseBody = false
    )

    fun standard() = LoggingHook(
        logLevel = LoggingHook.LogLevel.INFO,
        logRequestHeaders = false,
        logResponseHeaders = false,
        logRequestBody = true,
        logResponseBody = true,
        maxBodyLength = 500
    )

    fun debug() = LoggingHook(
        logLevel = LoggingHook.LogLevel.DEBUG,
        logRequestHeaders = true,
        logResponseHeaders = true,
        logRequestBody = true,
        logResponseBody = true,
        maxBodyLength = 2000,
        prettyPrintJson = true
    )

    fun verbose() = LoggingHook(
        logLevel = LoggingHook.LogLevel.VERBOSE,
        logRequestHeaders = true,
        logResponseHeaders = true,
        logRequestBody = true,
        logResponseBody = true,
        maxBodyLength = 5000,
        prettyPrintJson = true
    )
}


class AuthHook(private val token: String) : KyHook {
    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        request.headers["Authorization"] = "Bearer $token"
        return request
    }
}

class RetryHook : KyHook {
    override suspend fun beforeRetry(request: KyRequest, error: Throwable, retryCount: Int): KyRequest {
        println("🔄 Retrying ${request.method} ${request.url} (attempt $retryCount)")
        return request
    }
}

// 使用示例
/*
// 基本使用
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    headers = mapOf("Content-Type" to "application/json"),
    hooks = listOf(
        LoggingHook(),
        AuthHook("your-token-here"),
        RetryHook()
    )
)
// 使用示例
/*
// 不同级别的日志配置
val minimalKy = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(LoggingPresets.minimal())
)

val debugKy = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(LoggingPresets.debug())
)

// 自定义配置
val customKy = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(
        LoggingHook(
            logLevel = LoggingHook.LogLevel.INFO,
            logRequestHeaders = true,
            logResponseHeaders = false,
            maxBodyLength = 1000,
            prettyPrintJson = true,
            logRequestBody = true,
            logResponseBody = true
        )
    )
)

// 在协程中使用
lifecycleScope.launch {
    try {
        // GET 请求 - 会打印格式化的 JSON 响应
        val response = debugKy.get("/users")

        // POST 请求 - 会打印请求体和响应体
        val newUser = mapOf("name" to "John", "email" to "john@example.com")
        val createResponse = debugKy.post("/users", body = newUser)

    } catch (e: KyException) {
        // 错误也会被日志记录
    }
}

// 输出示例:
// 🚀 [GET   ] https://api.example.com/users
//    📋 Request Headers:
//       Content-Type: application/json
//       Authorization: ***1234
//    📤 Request Body:
//       [Empty Request]
// ✅ [GET   ] 200 OK https://api.example.com/users
//    📥 Response Body:
//       [
//         {
//           "id": 1,
//           "name": "John Doe",
//           "email": "john@example.com"
//         },
//         {
//           "id": 2,
//           "name": "Jane Smith",
//           "email": "jane@example.com"
//         }
//       ]
*/