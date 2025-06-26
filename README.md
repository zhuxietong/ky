# 发布到本地仓库
./gradlew publishToMavenLocal

# 发布到指定仓库
./gradlew publish

# 只发布到特定仓库
./gradlew publishMavenPublicationToLocalRepository
./gradlew publishMavenPublicationToGitHubPackagesRepository



看到你的 `build.gradle.kts` 配置很完整！现在的问题是 GitHub Packages 的连接问题。让我们逐步解决：

## 1. 首先检查 GitHub Token 配置

### 创建 `gradle.properties` 文件（在项目根目录）：

```properties
# GitHub 认证信息
gpr.user=zhuxietong
gpr.key=你的GitHub_Personal_Access_Token

# TLS 配置
systemProp.https.protocols=TLSv1.2,TLSv1.3
systemProp.jdk.tls.client.protocols=TLSv1.2,TLSv1.3
```

### 确保 GitHub Token 有正确权限：

1. 访问 GitHub Settings → Developer settings → Personal access tokens → Tokens (classic)
2. 创建新 token 或检查现有 token
3. 必须勾选这些权限：
    - ✅ `write:packages`
    - ✅ `read:packages`
    - ✅ `repo` (如果是私有仓库)

## 2. 先测试本地发布

```bash
# 发布到本地仓库测试
./gradlew publishMavenPublicationToLocalRepository

# 检查生成的文件
ls build/repo/com/zhuxietong/ky/1.0.0/
```

## 3. 推荐使用 JitPack（更简单）

由于 GitHub Packages 经常有网络问题，建议使用 JitPack：

### 步骤 1：推送代码到 GitHub

```bash
git add .
git commit -m "Release v1.0.0"
git push origin main
```

### 步骤 2：创建 Release

```bash
git tag v1.0.0
git push origin v1.0.0
```

### 步骤 3：在 GitHub 上创建 Release

1. 访问 `https://github.com/zhuxietong/ky`
2. 点击 "Releases" → "Create a new release"
3. 选择 tag `v1.0.0`
4. 发布 Release

### 步骤 4：验证 JitPack

访问：`https://jitpack.io/#zhuxietong/ky`

## 4. 使用你的库

### 在其他项目的 build.gradle.kts 中：

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
    mavenCentral()
}

dependencies {
    implementation("com.github.zhuxietong:ky:v1.0.0")
}
```

## 5. 如果坚持使用 GitHub Packages

### 修改 build.gradle.kts 添加更多配置：

```kotlin
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            // 添加更多元数据
            pom {
                name.set("ky")
                description.set("A Kotlin HTTP client library")
                url.set("https://github.com/zhuxietong/ky")
                
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                
                developers {
                    developer {
                        id.set("zhuxietong")
                        name.set("zhuxietong")
                    }
                }
                
                scm {
                    url.set("https://github.com/zhuxietong/ky")
                    connection.set("scm:git:git://github.com/zhuxietong/ky.git")
                    developerConnection.set("scm:git:ssh://git@github.com/zhuxietong/ky.git")
                }
            }

            artifact(tasks.register<Jar>("sourcesJar") {
                archiveClassifier.set("sources")
                from(sourceSets.main.get().allSource)
            })
        }
    }

    repositories {
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
```

## 6. 调试命令

```bash
# 查看详细日志
./gradlew publish --info

# 只发布到本地
./gradlew publishToMavenLocal

# 检查配置
./gradlew publishMavenPublicationToLocalRepository --dry-run
```

## 我的建议

1. **立即可用**：先用 `./gradlew publishToMavenLocal` 发布到本地测试
2. **长期方案**：使用 JitPack，因为它更稳定且不需要认证配置
3. **备选方案**：如果一定要用 GitHub Packages，确保网络稳定时再试

你想先试试哪种方案？我建议从 JitPack 开始，因为它最简单可靠。