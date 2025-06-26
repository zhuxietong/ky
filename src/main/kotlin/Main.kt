package com.zhuxietong

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

@Serializable
data class User(
    val id: Int,
    val name: String,
    val email: String
)

fun main() {
    val jsonString = """
        {
            "id": 1,
            "name": "John Doe",
            "email": "john@example.com"
        }
    """.trimIndent()

    // 基本用法
    val user = Json.decodeFromString<User>(jsonString)
    println(user) // User(id=1, name=John Doe, email=john@example.com)
}
