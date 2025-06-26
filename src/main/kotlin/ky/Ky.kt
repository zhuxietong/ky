package com.zhuxietong.ky

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
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
class LoggingHook : KyHook {
    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        println("🚀 ${request.method} ${request.url}")
        return request
    }

    override suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse {
        println("✅ ${response.status} ${request.method} ${request.url}")
        return response
    }

    override suspend fun onError(request: KyRequest, error: Throwable): Throwable {
        println("❌ Error ${request.method} ${request.url}: ${error.message}")
        return error
    }
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

// 在协程中使用
lifecycleScope.launch {
    try {
        // GET 请求
        val response = ky.get("/users")
        val users = response.json<List<User>>()

        // POST 请求
        val newUser = User(name = "John", email = "john@example.com")
        val createResponse = ky.post("/users", body = newUser)

        // 带重试的请求
        val retryResponse = ky.request(
            url = "/api/data",
            method = "GET",
            retries = 3
        )

    } catch (e: KyException) {
        println("Request failed: ${e.message}")
        println("Status: ${e.response?.status}")
    }
}

// 扩展实例
val apiV2 = ky.extend(
    baseUrl = "https://api.example.com/v2",
    headers = mapOf("API-Version" to "2.0")
)
*/
