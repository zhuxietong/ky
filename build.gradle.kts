plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"  // 版本要一致

}

group = "com.zhuxietong"
version = "1.0-SNAPSHOT"

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
kotlin {
    jvmToolchain(22)
}

allprojects {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/zhuxietong/ky")
            credentials {
                username = "zhuxietong" // 你的GitHub用户名
                password = "你的GitHub Token" // 需要有read:packages权限
            }
        }
    }
}