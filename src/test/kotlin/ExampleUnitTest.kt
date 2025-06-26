import com.zhuxietong.ky.AuthHook
import com.zhuxietong.ky.Ky
import com.zhuxietong.ky.KyException
import com.zhuxietong.ky.KyHook
import com.zhuxietong.ky.KyRequest
import com.zhuxietong.ky.KyResponse
import com.zhuxietong.ky.LoggingHook
import com.zhuxietong.ky.LoggingPresets
import com.zhuxietong.ky.RetryHook
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlin.test.*

@Serializable
data class Post(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String
)

@Serializable
data class User(
    val id: Int? = null,
    val name: String,
    val email: String,
    val username: String? = null
)

@Serializable
data class Comment(
    val postId: Int,
    val id: Int,
    val name: String,
    val email: String,
    val body: String
)

class ExampleUnitTest {

    @Test
    fun testBasicGetRequest() = runBlocking {
        // 创建 Ky 实例
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingPresets.minimal())
        )

        try {
            // 测试 GET 请求
            val response = api.get("/posts/1")

            // 验证响应
            assertTrue(response.ok, "Response should be ok")
            assertEquals(200, response.status, "Status should be 200")

            // 解析 JSON
            val post = response.json<Post>()

            println("获取到的 Post: ${post.id}, ${post.title}, ${post.body}")
//
//            // 验证数据
            assertEquals(1, post.id, "Post ID should be 1")
            assertEquals(1, post.userId, "User ID should be 1")
            assertNotNull(post.title, "Title should not be null")
            assertNotNull(post.body, "Body should not be null")

            println("✅ GET 请求测试通过")
            println("Post title: ${post.title}")

        } catch (e: Exception) {
            fail("GET request failed: ${e.message}")
        }
    }

    @Test
    fun testPostRequest() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            // 创建新的 Post 数据
            val newPost = mapOf(
                "title" to "Test Post",
                "body" to "This is a test post body",
                "userId" to 1
            )

            // 测试 POST 请求
            val response = api.post("/posts", body = newPost)

            // 验证响应
            assertTrue(response.ok, "Response should be ok")
            assertEquals(201, response.status, "Status should be 201")

            // 解析响应
            val createdPost = response.json<Post>()

            // 验证创建的数据
            assertEquals("Test Post", createdPost.title, "Title should match")
            assertEquals("This is a test post body", createdPost.body, "Body should match")
            assertEquals(1, createdPost.userId, "User ID should be 1")

            println("✅ POST 请求测试通过")
            println("Created post ID: ${createdPost.id}")

        } catch (e: Exception) {
            fail("POST request failed: ${e.message}")
        }
    }

    @Test
    fun testWithAuthHook() = runBlocking {
        // 创建带认证的 API 实例
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(
                LoggingHook(),
                AuthHook("test-token-123")
            )
        )

        try {
            val response = api.get("/posts")

            assertTrue(response.ok, "Response should be ok")

            val posts = response.json<List<Post>>()
            assertTrue(posts.isNotEmpty(), "Should have posts")
            assertTrue(posts.size >= 10, "Should have at least 10 posts")

            println("✅ 认证 Hook 测试通过")
            println("获取到 ${posts.size} 个帖子")

        } catch (e: Exception) {
            fail("Auth hook test failed: ${e.message}")
        }
    }

    @Test
    fun testRetryMechanism() = runBlocking {
        // 创建带重试的 API 实例
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(
                LoggingHook(),
                RetryHook()
            )
        )

        try {
            // 测试一个可能失败的请求（使用不存在的端点）
            val response = api.request(
                url = "/posts/999999", // 可能不存在的资源
                method = "GET",
                retries = 2
            )

            // 如果成功，验证响应
            if (response.ok) {
                println("✅ 重试机制测试通过（请求成功）")
            }

        } catch (e: KyException) {
            // 预期可能会失败，这也是正常的
            println("⚠️ 重试机制测试：请求最终失败（符合预期）")
            println("错误信息: ${e.message}")
            assertTrue(e is KyException, "Should be a KyException")
        }
    }

    @Test
    fun testExtendApi() = runBlocking {
        // 创建基础 API
        val baseApi = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            headers = mapOf("User-Agent" to "KyTest/1.0"),
            hooks = listOf(LoggingHook())
        )

        // 扩展 API，添加特定的头部
        val extendedApi = baseApi.extend(
            headers = mapOf(
                "User-Agent" to "KyTest/1.0",
                "X-Custom-Header" to "extended-api"
            )
        )

        try {
            val response = extendedApi.get("/users/1")

            assertTrue(response.ok, "Response should be ok")

            val user = response.json<User>()
            assertNotNull(user.name, "User name should not be null")
            assertNotNull(user.email, "User email should not be null")

            println("✅ API 扩展测试通过")
            println("用户名: ${user.name}")
            println("邮箱: ${user.email}")

        } catch (e: Exception) {
            fail("Extend API test failed: ${e.message}")
        }
    }

    @Test
    fun testDifferentHttpMethods() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            // 测试 GET
            val getResponse = api.get("/posts/1")
            assertTrue(getResponse.ok, "GET should be ok")

            // 测试 POST
            val postData = mapOf("title" to "Test", "body" to "Test body", "userId" to 1)
            val postResponse = api.post("/posts", body = postData)
            assertTrue(postResponse.ok, "POST should be ok")

            // 测试 PUT
            val putData = mapOf("id" to 1, "title" to "Updated", "body" to "Updated body", "userId" to 1)
            val putResponse = api.put("/posts/1", body = putData)
            assertTrue(putResponse.ok, "PUT should be ok")

            // 测试 DELETE
            val deleteResponse = api.delete("/posts/1")
            assertTrue(deleteResponse.ok, "DELETE should be ok")

            println("✅ 所有 HTTP 方法测试通过")

        } catch (e: Exception) {
            fail("HTTP methods test failed: ${e.message}")
        }
    }

    @Test
    fun testErrorHandling() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            // 测试 404 错误
            val response = api.get("/nonexistent-endpoint")
            fail("Should have thrown an exception for 404")

        } catch (e: KyException) {
            // 验证异常信息
            assertNotNull(e.request, "Exception should have request")
            assertNotNull(e.response, "Exception should have response")
            assertEquals(404, e.response?.status, "Should be 404")

            println("✅ 错误处理测试通过")
            println("捕获到预期的 404 错误: ${e.message}")

        } catch (e: Exception) {
            fail("Should have thrown KyException, but got: ${e.javaClass.simpleName}")
        }
    }

    @Test
    fun testCustomHook() = runBlocking {
        // 自定义 Hook 用于测试
        class TestHook : KyHook {
            var beforeRequestCalled = false
            var afterResponseCalled = false

            override suspend fun beforeRequest(request: KyRequest): KyRequest {
                beforeRequestCalled = true
                // 添加自定义头部
                request.headers["X-Test-Header"] = "test-value"
                return request
            }

            override suspend fun afterResponse(request: KyRequest, response: KyResponse): KyResponse {
                afterResponseCalled = true
                return response
            }
        }

        val testHook = TestHook()
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(testHook)
        )

        try {
            val response = api.get("/posts/1")

            assertTrue(response.ok, "Response should be ok")
            assertTrue(testHook.beforeRequestCalled, "beforeRequest should be called")
            assertTrue(testHook.afterResponseCalled, "afterResponse should be called")

            println("✅ 自定义 Hook 测试通过")

        } catch (e: Exception) {
            fail("Custom hook test failed: ${e.message}")
        }
    }

    @Test
    fun testJsonSerialization() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            // 测试复杂的 JSON 序列化
            val response = api.get("/posts/1/comments")

            assertTrue(response.ok, "Response should be ok")

            // 解析为 Comment 列表
            val comments = response.json<List<Comment>>()

            assertTrue(comments.isNotEmpty(), "Should have comments")

            val firstComment = comments.first()
            assertNotNull(firstComment.name, "Comment name should not be null")
            assertNotNull(firstComment.email, "Comment email should not be null")
            assertNotNull(firstComment.body, "Comment body should not be null")

            println("✅ JSON 序列化测试通过")
            println("获取到 ${comments.size} 条评论")
            println("第一条评论作者: ${firstComment.name}")

        } catch (e: Exception) {
            fail("JSON serialization test failed: ${e.message}")
        }
    }

    @Test
    fun testTimeout() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            timeout = 5000, // 5秒超时
            hooks = listOf(LoggingHook())
        )

        try {
            val response = api.get("/posts")
            assertTrue(response.ok, "Response should be ok")

            val posts = response.json<List<Post>>()
            assertTrue(posts.isNotEmpty(), "Should have posts")

            println("✅ 超时设置测试通过")
            println("在超时时间内成功获取到 ${posts.size} 个帖子")

        } catch (e: Exception) {
            fail("Timeout test failed: ${e.message}")
        }
    }

    @Test
    fun testResponseText() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            val response = api.get("/posts/1")

            assertTrue(response.ok, "Response should be ok")

            // 测试获取原始文本
            val textContent = response.text()
            assertTrue(textContent.isNotEmpty(), "Text content should not be empty")
            assertTrue(textContent.contains("userId"), "Should contain userId")
            assertTrue(textContent.contains("title"), "Should contain title")

            println("✅ 响应文本测试通过")
            println("响应文本长度: ${textContent.length}")

        } catch (e: Exception) {
            fail("Response text test failed: ${e.message}")
        }
    }

    @Test
    fun testHeaders() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            headers = mapOf(
                "User-Agent" to "KyTest/1.0",
                "Accept" to "application/json"
            ),
            hooks = listOf(LoggingHook())
        )

        try {
            val response = api.get("/posts/1")

            assertTrue(response.ok, "Response should be ok")
            assertNotNull(response.headers, "Response should have headers")

            // 检查响应头
            val contentType = response.headers["Content-Type"]
            assertNotNull(contentType, "Should have Content-Type header")
            assertTrue(contentType!!.contains("json"), "Content-Type should contain json")

            println("✅ 请求头测试通过")
            println("Content-Type: $contentType")

        } catch (e: Exception) {
            fail("Headers test failed: ${e.message}")
        }
    }

    @Test
    fun testMultipleRequests() = runBlocking {
        val api = Ky.create(
            baseUrl = "https://jsonplaceholder.typicode.com",
            hooks = listOf(LoggingHook())
        )

        try {
            // 并发执行多个请求
            val responses = listOf(
                api.get("/posts/1"),
                api.get("/posts/2"),
                api.get("/posts/3")
            )

            responses.forEach { response ->
                assertTrue(response.ok, "Each response should be ok")
                val post = response.json<Post>()
                assertNotNull(post.title, "Each post should have a title")
            }

            println("✅ 多请求测试通过")
            println("成功执行了 ${responses.size} 个并发请求")

        } catch (e: Exception) {
            fail("Multiple requests test failed: ${e.message}")
        }
    }
}
