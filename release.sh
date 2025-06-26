#!/bin/bash

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 脚本配置
BUILD_FILE="build.gradle.kts"
BACKUP_FILE="${BUILD_FILE}.backup"

# 检查 build.gradle.kts 是否存在
if [ ! -f "$BUILD_FILE" ]; then
    echo -e "${RED}错误: $BUILD_FILE 文件不存在${NC}"
    exit 1
fi

# 检查 gradlew 是否存在
if [ ! -f "./gradlew" ]; then
    echo -e "${RED}错误: gradlew 文件不存在${NC}"
    exit 1
fi

# 获取当前分支名
current_branch=$(git rev-parse --abbrev-ref HEAD)
if [ -z "$current_branch" ]; then
    echo -e "${RED}错误: 无法获取当前分支名${NC}"
    exit 1
fi

echo -e "${BLUE}当前分支: $current_branch${NC}"

# 提取当前版本号
echo -e "${BLUE}正在提取当前版本号...${NC}"
current_version=$(grep -E '^version = ".*"' "$BUILD_FILE" | sed -E 's/version = "(.*)"/\1/')

if [ -z "$current_version" ]; then
    echo -e "${RED}错误: 无法从 $BUILD_FILE 中提取版本号${NC}"
    exit 1
fi

echo -e "${GREEN}当前版本: $current_version${NC}"

# 解析版本号 (支持 x.y.z 格式)
if [[ $current_version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    major=${BASH_REMATCH[1]}
    minor=${BASH_REMATCH[2]}
    patch=${BASH_REMATCH[3]}

    # 递增补丁版本号
    new_patch=$((patch + 1))
    new_version="${major}.${minor}.${new_patch}"
else
    echo -e "${RED}错误: 版本号格式不正确，期望格式为 x.y.z${NC}"
    exit 1
fi

echo -e "${GREEN}新版本: $new_version${NC}"

# 询问用户确认版本号
echo -e "${YELLOW}是否使用新版本号 $new_version? (y/n/custom)${NC}"
read -r version_confirm

case $version_confirm in
    [Yy]* )
        # 使用计算出的新版本号
        ;;
    [Nn]* )
        echo -e "${YELLOW}操作已取消${NC}"
        exit 0
        ;;
    [Cc]* | custom )
        echo -e "${YELLOW}请输入自定义版本号:${NC}"
        read -r custom_version
        if [[ $custom_version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            new_version=$custom_version
            echo -e "${GREEN}使用自定义版本: $new_version${NC}"
        else
            echo -e "${RED}错误: 版本号格式不正确${NC}"
            exit 1
        fi
        ;;
    * )
        echo -e "${RED}无效输入，操作已取消${NC}"
        exit 1
        ;;
esac

# 备份原文件
cp "$BUILD_FILE" "$BACKUP_FILE"
echo -e "${BLUE}已创建备份文件: $BACKUP_FILE${NC}"

# 更新版本号
echo -e "${BLUE}正在更新版本号...${NC}"
if [[ "$OSTYPE" == "darwin"* ]]; then
    # macOS
    sed -i '' "s/version = \"$current_version\"/version = \"$new_version\"/" "$BUILD_FILE"
else
    # Linux
    sed -i "s/version = \"$current_version\"/version = \"$new_version\"/" "$BUILD_FILE"
fi

# 验证更新是否成功
updated_version=$(grep -E '^version = ".*"' "$BUILD_FILE" | sed -E 's/version = "(.*)"/\1/')
if [ "$updated_version" != "$new_version" ]; then
    echo -e "${RED}错误: 版本号更新失败${NC}"
    # 恢复备份
    mv "$BACKUP_FILE" "$BUILD_FILE"
    exit 1
fi

echo -e "${GREEN}版本号已更新: $current_version → $new_version${NC}"

# 显示更改内容
echo -e "${BLUE}更改内容预览:${NC}"
echo -e "${YELLOW}--- 旧版本${NC}"
echo -e "${RED}- version = \"$current_version\"${NC}"
echo -e "${YELLOW}+++ 新版本${NC}"
echo -e "${GREEN}+ version = \"$new_version\"${NC}"

# 检查 Git 状态
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo -e "${RED}错误: 当前目录不是 Git 仓库${NC}"
    # 恢复备份
    mv "$BACKUP_FILE" "$BUILD_FILE"
    exit 1
fi

# 检查是否有未提交的更改
if [ -n "$(git status --porcelain)" ]; then
    echo -e "${YELLOW}警告: 检测到未提交的更改${NC}"
    git status --short
    echo ""
fi

# 最终确认
echo -e "${YELLOW}========================================${NC}"
echo -e "${YELLOW}准备执行以下操作:${NC}"
echo -e "${BLUE}1. git add .${NC}"
echo -e "${BLUE}2. git commit -m \"Release $new_version\"${NC}"
echo -e "${BLUE}3. git push origin main${NC}"
echo -e "${BLUE}4. git tag $new_version${NC}"
echo -e "${BLUE}5. git push origin $new_version${NC}"
echo -e "${BLUE}6. ./gradlew publish${NC}"
if [ "$current_branch" != "main" ]; then
    echo -e "${BLUE}7. git push origin $current_branch${NC}"
fi
echo -e "${YELLOW}========================================${NC}"
echo ""
echo -e "${YELLOW}是否确认提交并发布? (y/n)${NC}"
read -r final_confirm

case $final_confirm in
    [Yy]* )
        echo -e "${GREEN}开始发布流程...${NC}"
        ;;
    * )
        echo -e "${YELLOW}操作已取消，正在恢复原文件...${NC}"
        mv "$BACKUP_FILE" "$BUILD_FILE"
        echo -e "${GREEN}文件已恢复${NC}"
        exit 0
        ;;
esac

# 计算总步骤数
total_steps=6
if [ "$current_branch" != "main" ]; then
    total_steps=7
fi

# 执行 Git 操作
echo -e "${BLUE}步骤 1/$total_steps: 添加文件到暂存区...${NC}"
if git add .; then
    echo -e "${GREEN}✓ git add 完成${NC}"
else
    echo -e "${RED}✗ git add 失败${NC}"
    mv "$BACKUP_FILE" "$BUILD_FILE"
    exit 1
fi

echo -e "${BLUE}步骤 2/$total_steps: 提交更改...${NC}"
if git commit -m "Release $new_version"; then
    echo -e "${GREEN}✓ git commit 完成${NC}"
else
    echo -e "${RED}✗ git commit 失败${NC}"
    exit 1
fi

echo -e "${BLUE}步骤 3/$total_steps: 推送到远程仓库 main 分支...${NC}"
if git push origin main; then
    echo -e "${GREEN}✓ git push origin main 完成${NC}"
else
    echo -e "${RED}✗ git push origin main 失败${NC}"
    exit 1
fi

echo -e "${BLUE}步骤 4/$total_steps: 创建标签...${NC}"
if git tag "$new_version"; then
    echo -e "${GREEN}✓ git tag 完成${NC}"
else
    echo -e "${RED}✗ git tag 失败${NC}"
    exit 1
fi

echo -e "${BLUE}步骤 5/$total_steps: 推送标签...${NC}"
if git push origin "$new_version"; then
    echo -e "${GREEN}✓ git push origin tag 完成${NC}"
else
    echo -e "${RED}✗ git push origin tag 失败${NC}"
    exit 1
fi

echo -e "${BLUE}步骤 6/$total_steps: 本地发布...${NC}"
echo -e "${YELLOW}正在执行 ./gradlew publish...${NC}"
if ./gradlew publish; then
    echo -e "${GREEN}✓ ./gradlew publish 完成${NC}"
else
    echo -e "${RED}✗ ./gradlew publish 失败${NC}"
    echo -e "${YELLOW}注意: Git 操作已完成，但本地发布失败${NC}"
    echo -e "${YELLOW}你可以稍后手动执行: ./gradlew publish${NC}"
    # 不退出，因为 Git 操作已经成功
fi

# 如果当前分支不是 main，推送当前分支
if [ "$current_branch" != "main" ]; then
    echo -e "${BLUE}步骤 7/$total_steps: 推送当前分支 ($current_branch)...${NC}"
    if git push origin "$current_branch"; then
        echo -e "${GREEN}✓ git push origin $current_branch 完成${NC}"
    else
        echo -e "${RED}✗ git push origin $current_branch 失败${NC}"
        echo -e "${YELLOW}注意: 主要发布流程已完成，但当前分支推送失败${NC}"
        echo -e "${YELLOW}你可以稍后手动执行: git push origin $current_branch${NC}"
    fi
fi

# 清理备份文件
rm -f "$BACKUP_FILE"

# 完成提示
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}🎉 发布完成!${NC}"
echo -e "${GREEN}版本: $new_version${NC}"
echo -e "${GREEN}当前分支: $current_branch${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo -e "${BLUE}已完成的操作:${NC}"
echo -e "${GREEN}✓ 版本号已更新${NC}"
echo -e "${GREEN}✓ 代码已提交并推送到 main 分支${NC}"
echo -e "${GREEN}✓ 标签已创建并推送${NC}"
echo -e "${GREEN}✓ 本地发布已执行${NC}"
if [ "$current_branch" != "main" ]; then
    echo -e "${GREEN}✓ 当前分支 ($current_branch) 已推送${NC}"
fi
echo ""
echo -e "${BLUE}后续自动化流程:${NC}"
echo -e "${BLUE}1. GitHub Actions 将自动创建 Release${NC}"
echo -e "${BLUE}2. JitPack 将自动构建包${NC}"
echo -e "${BLUE}3. 几分钟后可在 https://jitpack.io/#zhuxietong/ky/$new_version 查看状态${NC}"
echo ""
echo -e "${YELLOW}安装命令:${NC}"
echo -e "${GREEN}implementation(\"com.github.zhuxietong:ky:$new_version\")${NC}"

# 显示分支状态
echo ""
echo -e "${BLUE}分支状态:${NC}"
if [ "$current_branch" = "main" ]; then
    echo -e "${GREEN}当前在 main 分支，所有更改已同步${NC}"
else
    echo -e "${YELLOW}当前在 $current_branch 分支${NC}"
    echo -e "${GREEN}main 分支和 $current_branch 分支都已更新${NC}"
fi
