# Ky for Kotlin

一个受 JavaScript [Ky](https://github.com/sindresorhus/ky) 启发的现代 Kotlin HTTP 客户端，基于 OkHttp 和 Kotlin
Coroutines 构建。

## ✨ 特性

- 🚀 **现代异步**: 基于 Kotlin Coroutines 的完全异步 API
- 🎯 **简洁易用**: 简单直观的 API 设计，支持链式调用
- 🔧 **高度可配置**: 灵活的配置选项和实例扩展
- 🪝 **强大的 Hook 系统**: 支持请求/响应拦截和处理
- 🔄 **自动重试**: 内置重试机制，支持自定义重试策略
- 📦 **JSON 序列化**: 内置 Kotlinx Serialization 支持
- 🛡️ **类型安全**: 完全的 Kotlin 类型安全支持

## 📦 安装

确保在项目级别的 `build.gradle.kts` 或 `settings.gradle.kts` 中添加了 JitPack：

```kotlin
// 在 build.gradle.kts 中
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // 添加 JitPack 仓库
    }
}
```

```kotlin
// 在 settings.gradle.kts 中
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

**添加依赖：**

```kotlin

plugins {
    kotlin("plugin.serialization") version "2.1.10"
}

dependencies {
    implementation("com.zhuxietong:ky:1.0.8")
}
```

## 🚀 快速开始

### 基本用法

```kotlin
// 创建 Ky 实例
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    headers = mapOf("Content-Type" to "application/json")
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

        // 其他 HTTP 方法
        val putResponse = ky.put("/users/1", body = updatedUser)
        val deleteResponse = ky.delete("/users/1")

    } catch (e: KyException) {
        println("请求失败: ${e.message}")
    }
}
```

### 高级配置

```kotlin
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    timeout = 60000, // 60秒超时
    headers = mapOf(
        "Content-Type" to "application/json",
        "User-Agent" to "MyApp/1.0"
    )
)

// 带重试的请求
val response = ky.request(
    url = "/api/data",
    method = "GET",
    retries = 3 // 最多重试3次
)
```

### 实例扩展

```kotlin
// 基础实例
val baseKy = Ky.create(baseUrl = "https://api.example.com")

// 为不同的 API 版本创建扩展实例
val v1Api = baseKy.extend(
    baseUrl = "https://api.example.com/v1",
    headers = mapOf("API-Version" to "1.0")
)

val v2Api = baseKy.extend(
    baseUrl = "https://api.example.com/v2",
    headers = mapOf("API-Version" to "2.0"),
    timeout = 30000
)
```

## 🪝 Hook 系统

Hook 系统是 Ky 的核心特性之一，允许你在请求的不同阶段插入自定义逻辑。

### Hook 接口

```kotlin
interface KyHook {
    suspend fun beforeRequest(request: KyRequest): KyRequest = request
    suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse = response
    suspend fun beforeRetry(request: KyRequest, error: Throwable, retryCount: Int): KyRequest = request
    suspend fun onError(request: KyRequest, error: Throwable): Throwable = error
}
```

### 内置 Hook 示例

#### 1. 日志 Hook

```kotlin
class LoggingHook(
    private val logLevel: LogLevel = LogLevel.INFO,
    private val logRequestHeaders: Boolean = false,
    private val logResponseHeaders: Boolean = false,
    private val maxBodyLength: Int = 1000,
    private val prettyPrintJson: Boolean = true
) : KyHook {

    enum class LogLevel { NONE, ERROR, WARN, INFO, DEBUG, VERBOSE }

    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        println("🚀 [${request.method.padEnd(6)}] ${request.url}")
        return request
    }

    override suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse {
        val icon = if (response.ok) "✅" else "❌"
        println("$icon [${request.method.padEnd(6)}] ${response.status} ${request.url}")
        return response
    }
}

// 预设配置
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(LoggingPresets.debug()) // 使用调试级别日志
)
```

#### 2. 认证 Hook

```kotlin
class AuthHook(private val token: String) : KyHook {
    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        request.headers["Authorization"] = "Bearer $token"
        return request
    }
}

// 使用认证 Hook
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(AuthHook("your-jwt-token"))
)
```

#### 3. 重试 Hook

```kotlin
class RetryHook : KyHook {
    override suspend fun beforeRetry(request: KyRequest, error: Throwable, retryCount: Int): KyRequest {
        println("🔄 重试 ${request.method} ${request.url} (第 $retryCount 次)")
        return request
    }
}
```

#### 4. 缓存 Hook

```kotlin
class CacheHook(private val cache: MutableMap<String, KyResponse> = mutableMapOf()) : KyHook {
    override suspend fun beforeRequest(request: KyRequest): KyRequest {
        if (request.method == "GET") {
            cache[request.url]?.let { cachedResponse ->
                // 这里可以实现缓存逻辑
                println("💾 使用缓存: ${request.url}")
            }
        }
        return request
    }

    override suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse {
        if (request.method == "GET" && response.ok) {
            cache[request.url] = response
        }
        return response
    }
}
```

#### 5. 错误处理 Hook

```kotlin
class ErrorHandlingHook : KyHook {
    override suspend fun onError(request: KyRequest, error: Throwable): Throwable {
        return when (error) {
            is KyException -> {
                when (error.response?.status) {
                    401 -> UnauthorizedException("认证失败")
                    403 -> ForbiddenException("权限不足")
                    404 -> NotFoundException("资源不存在")
                    500 -> ServerErrorException("服务器内部错误")
                    else -> error
                }
            }
            else -> error
        }
    }
}
```

### 组合使用 Hook

```kotlin
val ky = Ky.create(
    baseUrl = "https://api.example.com",
    hooks = listOf(
        LoggingPresets.standard(),           // 标准日志
        AuthHook("your-token"),              // 认证
        RetryHook(),                         // 重试日志
        CacheHook(),                         // 缓存
        ErrorHandlingHook()                  // 错误处理
    )
)

lifecycleScope.launch {
    try {
        val response = ky.get("/users", retries = 2)
        val users = response.json<List<User>>()
    } catch (e: UnauthorizedException) {
        // 处理认证错误
        redirectToLogin()
    } catch (e: KyException) {
        // 处理其他 HTTP 错误
        showError(e.message)
    }
}
```

## 📝 响应处理

```kotlin
val response = ky.get("/api/data")

// JSON 反序列化
val data = response.json<ApiResponse<List<Item>>>()

// 纯文本
val text = response.text()

// 检查响应状态
if (response.ok) {
    println("请求成功: ${response.status}")
} else {
    println("请求失败: ${response.status} ${response.statusText}")
}

// 访问响应头
val contentType = response.headers["Content-Type"]
```

## 🔧 配置选项

```kotlin
val ky = Ky.create(
    baseUrl = "https://api.example.com",     // 基础 URL
    timeout = 30000,                         // 超时时间(毫秒)
    headers = mapOf(                         // 默认请求头
        "Content-Type" to "application/json",
        "User-Agent" to "MyApp/1.0"
    ),
    hooks = listOf(                          // Hook 列表
        LoggingHook(),
        AuthHook("token")
    )
)
```

## 🎯 最佳实践

### 1. 创建专用的 API 客户端

```kotlin
class ApiClient {
    private val ky = Ky.create(
        baseUrl = "https://api.example.com",
        hooks = listOf(
            LoggingPresets.standard(),
            AuthHook(getAuthToken()),
            ErrorHandlingHook()
        )
    )

    suspend fun getUsers(): List<User> {
        return ky.get("/users").json()
    }

    suspend fun createUser(user: User): User {
        return ky.post("/users", body = user).json()
    }
}
```

### 2. 环境配置

```kotlin
object ApiConfig {
    fun createKy(environment: Environment): Ky {
        val baseUrl = when (environment) {
            Environment.DEV -> "https://dev-api.example.com"
            Environment.STAGING -> "https://staging-api.example.com"
            Environment.PROD -> "https://api.example.com"
        }

        val hooks = mutableListOf<KyHook>().apply {
            if (environment != Environment.PROD) {
                add(LoggingPresets.debug())
            }
            add(AuthHook(getAuthToken()))
            add(ErrorHandlingHook())
        }

        return Ky.create(baseUrl = baseUrl, hooks = hooks)
    }
}
```

### 3. 错误处理

```kotlin
suspend fun safeApiCall(apiCall: suspend () -> KyResponse): Result<KyResponse> {
    return try {
        Result.success(apiCall())
    } catch (e: KyException) {
        when (e.response?.status) {
            401 -> Result.failure(AuthenticationException())
            403 -> Result.failure(AuthorizationException())
            else -> Result.failure(e)
        }
    } catch (e: Exception) {
        Result.failure(NetworkException(e))
    }
}
```

## 📄 许可证

MIT License

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

---

**Ky for Kotlin** - 让 HTTP 请求变得简单而强大 🚀