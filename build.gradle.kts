plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    `maven-publish`
}

group = "com.zhuxietong"
version = "1.0.9"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
}

tasks.test {
    useJUnitPlatform()
}

// 修改为 JVM 17
kotlin {
    jvmToolchain(17)
}

// 确保编译目标也是 17
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            // 添加更多元数据（可选）
            pom {
                name.set("Ky HTTP Client")
                description.set("A Kotlin HTTP client library")
                url.set("https://github.com/zhuxietong/ky")
            }
        }
    }
}
