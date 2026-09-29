# 手机清理 · Android

不用 root 的本地清理工具：按应用清缓存、按类型看文件，重复文件、内置播放器，还带病毒扫描。纯 Java + 原生 View，无 WebView。

- 应用下载（蒲公英分发页）：<https://www.pgyer.com/shoujiqingli-android>
- 也在 ima 知识号「Open apps」里发布（含使用说明与源码文档）

## 功能

- **一键清理**：缓存、残留、空文件夹、安装包等分类列出，勾选即删
- **应用专清**：按应用看占用、清缓存 —— 微信 / QQ / TIM / 抖音 / 快手 / 淘宝 / 支付宝 / 小红书 / B站 等常用应用开箱即有，也能「增加应用」自己加；只清该应用自己的缓存目录与临时文件，不动聊天记录、照片和文档
- **分类文件**：空文件夹 / 大文件 / 冗余文件 / 新文件 / 安装包 / 图片 / 视频 / 音频 / 文档 / 压缩包，每类显示个数与占用，点进去看列表、可多选删除
- **重复文件**：按内容哈希找重复，保留最优、批量删除
- **存储分析**：按文件来源看占用（微信 / QQ / 下载 / 相机 / 截图 / 蓝牙 / 录音 / 其他），点进来源直接看文件列表
- **媒体整理**：照片、视频、音乐按大小 / 时间排列，内置播放器可直接试听试看
- **病毒扫描**：对本机 APK/exe/so 等可执行文件算 SHA-256，比对在线病毒库（abuse.ch MalwareBazaar），命中可一键删除；也可选单个文件查毒或跳转 VirusTotal 核对
- **App 内自动更新**（loadly）+「开发者的其他 App」推荐页（蒲公英）

## 环境要求

| 项目 | 版本 |
|---|---|
| Gradle | 8.7 |
| JDK | 17 或 21 |
| Android Gradle Plugin | 8.6.1 |
| compileSdk / targetSdk | 36（Android 16） |
| minSdk | 30（Android 11） |

## 怎么编译

1. 复制 `local.properties.example` 为 `local.properties`，按注释填自己的密钥（不填也能编译，只是更新检查 / 推荐页 / 在线病毒库不可用）。
2. 自己生成签名库（本仓库不含签名文件）：

   ```bash
   keytool -genkeypair -v -keystore cleaner.keystore -alias calendar \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   在 `local.properties` 里填 `KEYSTORE_STORE_PASSWORD`、`KEYSTORE_KEY_PASSWORD`（别名默认 `calendar`）。
3. 打包：

   ```bash
   gradle assembleRelease
   # 产物：app/build/outputs/apk/release/app-release.apk
   ```

## 权限说明

- `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」）：全盘扫描与「应用专清」需要，System Settings 里手动授权
- `READ_MEDIA_IMAGES / VIDEO / AUDIO`：媒体列表与内置播放器
- `REQUEST_INSTALL_PACKAGES`：App 内更新安装
- 没有网络也能清理；联网只用于更新检查、推荐页和在线病毒库

## 说明

- **本仓库不含任何密钥、账号或签名文件**；`local.properties`、`*.keystore` 已写进 `.gitignore`
- 清理前均二次确认；「应用专清」只匹配 cache / code_cache / temp / log / thumbnails 这类缓存目录名
- 未做代码混淆（`minifyEnabled false`），方便阅读与二次开发

## 许可

未附许可证文件（作者保留权利）。如需开放使用，可加 MIT 或 Apache-2.0，告知即可。
