plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    `maven-publish` // 正确的插件名称
}


group = "com.zhuxietong"
version = "1.0.0"

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

// 现在可以使用 publishing 配置块了
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifact(tasks.register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from(sourceSets.main.get().allSource)
            })
        }
    }

    repositories {
        // 先发布到本地测试
        maven {
            name = "local"
            url = uri(layout.buildDirectory.dir("repo"))
        }

        // GitHub Packages 配置
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/zhuxietong/ky")
            credentials {
                username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_USERNAME")
                password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
