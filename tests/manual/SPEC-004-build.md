# SPEC-004 构建体系 — 手工验收清单

- 对应 spec：spec/implemented/SPEC-004-build.md
- 结果标记：P 通过 / F 失败 / N 未执行

## 用例

### TC-004-01 全量构建（AC-1, FR-4-1）
步骤：`python build.py app`；`python build.py server`。
预期：两条命令成功，dist/ 下生成 TVRemote.apk 与 TVRemoteServer.apk。
实际：（待填）　结果：（P/F/N）

### TC-004-02 安装运行（AC-2, FR-4-5）
步骤：两个 APK 安装到目标设备并打开。
预期：正常启动；再次安装（覆盖升级）不被签名校验拒绝。
实际：（待填）　结果：（P/F/N）

### TC-004-03 KeyConst 自动生成（AC-3, FR-4-3, NFR-4-5）
步骤：删除 KeyConst.java 后重新 `python build.py app`；`git status` 查看其是否入库。
预期：文件自动重新生成；git 不追踪（.gitignore 生效）。
实际：（待填）　结果：（P/F/N）

### TC-004-04 产物体积（AC-4, NFR-4-3）
步骤：查看 dist/ 下两个 APK 大小。
预期：均不超过 40 KB。
实际：（待填）　结果：（P/F/N）

### TC-004-05 SDK 自动定位（AC-5, FR-4-4）
步骤：换一台装了 JDK + Android SDK（默认路径）的 Windows 机器直接构建。
预期：无需配置路径即可构建成功。
实际：（待填）　结果：（P/F/N）
