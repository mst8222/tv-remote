# SPEC-004 构建体系

## 元信息
- 状态：implemented
- 创建：2026-10-21
- 更新：2026-10-21
- 优先级：high

## 变更记录
- 2026-10-21 创建：从 v1.0 已验证实现提炼成文

## 背景与目标

不用 Gradle，用一个 Python 脚本完成从源码到签名 APK 的全流程。目标是构建链
完全可控、产物极小（16–37 KB）、零第三方依赖，适合单文件手工维护的小项目。

## 功能需求（FR）

- FR-4-1 构建命令：`python build.py app` 产出 `dist/TVRemote.apk`；
  `python build.py server` 产出 `dist/TVRemoteServer.apk`。
- FR-4-2 构建链：aapt2（打包资源/生成 R）→ javac → d8（dex）→ 打包 APK →
  zipalign → apksigner 签名。
- FR-4-3 KeyConst 生成：构建 app 时从 `keys/adbkey.pk8` 与 `keys/cert.der`
  生成 `app/src/com/koco/tvremote/KeyConst.java`（含 base64 私钥与证书常量）。
- FR-4-4 SDK 自动定位：从 `%LOCALAPPDATA%\Android\Sdk` 自动找 build-tools 与
  platforms/android-35，无需配置。
- FR-4-5 签名：使用 keys/release.jks（别名 koco）签名并对齐。

## 非功能需求（NFR）

- NFR-4-1 需要 JDK 11+（当前验证环境 JDK 21）；编译目标 `-source 8 -target 8`
  （JDK 21 下 `-source 11` 不允许配 `-bootclasspath`）。
- NFR-4-2 minSdk 26（`java.util.Base64` 为 API 26+）；lambda 需把
  core-lambda-stubs.jar 加入 bootclasspath。
- NFR-4-3 产物体积：单 APK 16–37 KB 内。
- NFR-4-4 零第三方依赖：不下载任何依赖，仅用 JDK + Android SDK 自带工具。
- NFR-4-5 密钥不入库：keys/ 与 KeyConst.java 均在 .gitignore 中。

## 验收标准

- AC-1 干净环境下 `python build.py app` 与 `python build.py server` 均成功，
  dist/ 下产出对应 APK。 【手工】
- AC-2 产物可安装到目标设备并正常运行（覆盖安装签名一致）。 【手工】
- AC-3 构建后 KeyConst.java 自动生成且随 .gitignore 不入库。 【手工】
- AC-4 产物大小不超过 40 KB。 【手工】
- AC-5 换机器构建只需 JDK + Android SDK + keys/ 备份，无需其他配置。 【手工】

## 边界与已知限制

- 仅支持 Windows（依赖 `%LOCALAPPDATA%` 探测）。
- release.jks 口令硬编码在 build.py（局域网自用项目的取舍）。
- 不产出 AAB、不做多渠道、不做混淆。

## 实现映射

| 需求 | 文件 |
|---|---|
| FR-4-1 ~ FR-4-5, NFR-4-1 | build.py |

## 测试映射

- 手工：tests/manual/SPEC-004-build.md
