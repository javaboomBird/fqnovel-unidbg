# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

本项目基于 [anjia0532/unidbg-boot-server](https://github.com/anjia0532/unidbg-boot-server) 修改，使用 unidbg 模拟 Android ARM64 环境执行 fqnovel（番茄小说）的 `libmetasec_ml.so` 签名库，以生成合法的 API 请求签名，从而抓取小说内容。

技术栈：Spring Boot 2.6.3、Java 17、unidbg 0.9.8、Redis。

## 构建与运行

```bash
# 构建（默认跳过单元测试，因为 unidbg 初始化成本高）
./mvnw package -DskipTests

# 启动服务（端口 9999）
java -jar target/unidbg-boot-server-0.0.1-SNAPSHOT.jar

# 一键重启脚本（重新打包 + 停止旧进程 + 启动新进程，仅本地使用）
bash restart.sh

# 运行测试（需显式开启）
./mvnw test -Dmaven.test.skip=false
```

## 部署到阿里云 ECS

使用 `deploy.sh` 一键构建并部署到远程 Docker 容器：

```bash
bash deploy.sh
```

流程：本地构建 JAR → SCP 上传至 ECS → `docker cp` 注入容器 → `docker restart`，全程约 30 秒。

**换机器部署**时，私钥路径不同，通过环境变量指定：

```bash
DEPLOY_PEM=/path/to/your/cx.pem bash deploy.sh
```

**脚本配置项**（`deploy.sh` 顶部）：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `ECS_HOST` | `root@8.129.129.59` | ECS 登录地址 |
| `ECS_PEM` | `$DEPLOY_PEM` 或 `~/Downloads/aliyun/cx.pem` | SSH 私钥路径 |
| `CONTAINER` | `fqnovel-unidbg` | Docker 容器名 |
| `REMOTE_DIR` | `/opt/fqnovel` | ECS 上的部署目录 |

## 核心架构

### 签名生成流程

```
HTTP 请求 → Controller
    → FQEncryptServiceWorker（Worker 线程池，管理并发）
        → FQEncryptService（单个实例）
            → IdleFQ（unidbg 核心，持有 AndroidEmulator 实例）
                → 调用 libmetasec_ml.so 中偏移 0x168c80 的 native 函数
                → 返回签名字符串（X-Argus 等 header）
```

### 章节内容解密流程

```
FQNovelService.getChapterContent()
    → FQRegisterKeyService（获取 AES 密钥）
    → FqCrypto.decryptAndDecompressContent()（AES-128-CBC 解密 + GZIP 解压）
```

### 关键类职责

- **`IdleFQ`** (`unidbg/IdleFQ.java`) — unidbg 模拟器核心，实现 `AbstractJni` 补全 Java 环境，实现 `IOResolver` 拦截文件访问。从 classpath 资源复制到临时目录后加载 so 文件。每个实例持有独立的 `AndroidEmulator`，**非线程安全**。
- **`FQEncryptServiceWorker`** — 继承 unidbg `Worker`，管理 `FQEncryptService` 实例池。`async=true` 时使用多实例并发；`async=false` 时单实例 synchronized。
- **`FQEncryptService`** — 包装 IdleFQ，解析签名结果（key\nvalue 格式逐行分割），去除 `X-Neptune` header。
- **`FQNovelService`** — 调用 fqnovel 远程 API，处理书籍搜索/目录/章节，负责解密章节内容。
- **`FqCrypto`** — AES-128-CBC 加解密，注册密钥固定为 `ac25c67ddd8f38c1b37a2348828e222e`，解密后取前 16 字节（32 hex chars）作为章节内容密钥。
- **`FQRegisterKeyService`** — 向 fqnovel 注册接口申请 session key，缓存于 Redis。
- **`DeviceGeneratorService`** / **`DeviceManagementService`** — 生成/管理模拟设备信息，支持写入外部 `application.yml` 并重启服务。

## 所需资源文件

以下文件需手动准备，放置于 `src/main/resources/com/dragon/read/oversea/gp/`（**不在版本库中**）：

| 路径 | 说明 |
|------|------|
| `apk/番茄小说_6.8.1.32.apk` | fqnovel APK（版本 6.8.1.32） |
| `lib/libmetasec_ml.so` | 签名 so 库（从 APK 中提取） |
| `lib/libc++_shared.so` | C++ 共享库依赖 |
| `rootfs/` | unidbg 模拟根文件系统目录 |
| `other/ms_16777218.bin` | 证书文件（用于 MS 方法调用 case 16777218） |

## 配置说明

`application.yml` 中关键配置项：

```yaml
application:
  unidbg:
    dynarmic: false   # 是否使用 Dynarmic 引擎（更快但不稳定）
    verbose: false    # 是否打印 unidbg 详细调用日志
    async: false      # 是否启用多实例并发（true 时每个线程持有独立 IdleFQ 实例）
fq.api:
  base-url: ...       # fqnovel API 地址
  device.*:           # 模拟设备参数，影响签名和 API 请求的 User-Agent/Cookie 等
spring.redis:         # Redis 必须启动，用于缓存注册密钥
```

## API 接口列表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/fqnovel/book/{bookId}` | 获取书籍信息 |
| GET | `/api/fqnovel/directory/{bookId}` | 获取书籍目录 |
| GET/POST | `/api/fqnovel/chapter/{bookId}/{chapterId}` | 获取单章内容（避免频繁调用，易触发风控） |
| POST | `/api/fqnovel/chapters/batch` | 批量获取章节内容（推荐使用） |
| POST | `/api/fqnovel/fqsearch/book` | 搜索书籍 |
| POST | `/api/device/register-and-restart` | 注册新设备并重启（写入外部 yml） |

## 开发注意事项

- **unidbg 实例初始化很慢**（加载 so 约需数秒），启动时一次性完成，请勿重复创建。
- `IdleFQ` 中 native 函数调用偏移 `0x168c80` 与 so 版本强绑定，换 so 时必须重新确认。
- 设备信息（device-id、cdid、install-id）是风控的主要因素，单次风控封锁后需换设备信息重启。使用 `tools/batch_device_register_xml.py` 批量生成设备配置。
- 章节内容经过两层处理：先 AES-128-CBC 解密（密钥来自 RegisterKey 接口），再可能需要 GZIP 解压。
- 测试文件在 `src/test/resources/application.yml` 中有独立配置，与主配置分离。
