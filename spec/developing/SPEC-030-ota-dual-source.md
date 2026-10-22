# SPEC-030 OTA 双源探测（GitHub + Gitee）

## 元信息

- 状态：developing
- 创建：2026-10-22
- 更新：2026-10-22（开工）
- 优先级：high

## 变更记录

- 2026-10-22 创建并开工：GitHub 在国内网络下经常无法访问，
  v1.6.0 用户打开 App 看不到「有更新」标签，手动检查也失败。
  改为同时探测 GitHub 与 Gitee 的公开 Release，任一源有更高版本即视为有更新；
  下载优先 Gitee，失败回退 GitHub。
- 2026-10-22 实现完成：两端 Updater 双源并行探测、按版本合并下载源、下载逐源回退；
  版本升至 1.6.6（versionCode 10）。
  GitHub 与 Gitee 均已发布 v1.6.6 并上传双端 APK；
  Gitee 匿名 API 返回 `v1.6.6`，`TVRemote.apk` 匿名下载 SHA-256 与本地一致；
  H96 已安装盒端 1.6.6。手机端待安装后验证启动提示与下载安装。

## 背景与目标

SPEC-017/027 的 OTA 只依赖 GitHub `releases/latest` 与 GitHub 资产 API。
实测国内部分网络无法访问 `api.github.com`，启动探测静默失败，
用户既看不到「有更新」标签，也无法下载安装包。

目标：

- 双端（手机 App 与盒子端）同时查询 GitHub 与 Gitee 的 Release；
- 两个源中版本更高者决定更新提示，版本相同时仍提示（只要高于当前版本）；
- 下载优先走 Gitee 直链（国内可达），Gitee 不可用时回退 GitHub 资产 API；
- 单个源不可达不影响另一个源的结果。

Gitee 镜像仓库：`moston/tv-remote`（公开，内容与 GitHub 公开快照一致）。

## 功能需求（FR）

- FR-30-1 更新探测并发查询两个源：
  `https://api.github.com/repos/mst8222/tv-remote/releases/latest` 与
  `https://gitee.com/api/v5/repos/moston/tv-remote/releases`。
- FR-30-2 只接受包含本端 APK 资产的 Release：手机端 `TVRemote.apk`，
  盒子端 `TVRemoteServer.apk`。
- FR-30-3 取两个源中版本号最高的 Release 作为候选；仅当候选版本高于当前
  versionName 时显示「有更新」标签与更新说明。
- FR-30-4 两个源返回同一版本时合并下载源：Gitee 直链优先，GitHub 资产 API 次之。
- FR-30-5 下载按 FR-30-4 的顺序逐个尝试，前一个源失败（网络异常、HTTP 非 200、
  文件长度不符）后自动尝试下一个；全部失败才报网络错误。
- FR-30-6 任一源网络失败时不影响另一源；两个源都失败才向界面返回错误。
- FR-30-7 更新说明优先取 GitHub 的 `body`，为空时取 Gitee 的 `body`。

## 非功能需求（NFR）

- NFR-30-1 两个源并行查询，单源连接/读取超时 15 秒，最坏等待不超过约 30 秒。
- NFR-30-2 探测与下载仍在后台线程执行，不阻塞主线程。
- NFR-30-3 Gitee 只使用公开只读接口，不在客户端保存任何访问令牌。
- NFR-30-4 不改变现有安装流程（PackageInstaller + 待确认状态）。

## 验收标准

- [ ] AC-1 两个源都可达且版本相同时，更新标签正常显示，下载走 Gitee 直链。
- [ ] AC-2 仅 Gitee 可达（GitHub 超时）时，仍能探测到新版本并完成下载安装。
- [ ] AC-3 仅 GitHub 可达时，行为与 SPEC-017 一致。
- [ ] AC-4 两个源都不可达时，手动「检查更新」提示网络错误，App 不崩溃。
- [ ] AC-5 版本号相同不显示更新；Gitee 版本更高时按 Gitee 版本提示。
- [ ] AC-6 下载文件长度与资产 size 不一致时删除临时文件并尝试下一个源。
- [ ] AC-7 手机端与盒子端各自匹配自己的 APK 资产名，不交叉下载。

## 边界与已知限制

- Gitee 免费仓库对单文件与 Release 资产有大小限制，当前 APK 约 150~350 KB，远低于上限。
- Gitee 与 GitHub 的 Release 需要同时发布；只发布一个源时另一个源不报错，仅少一个候选。
- 访问 Gitee 需要外网；纯局域网环境两个源都不可用，此时只能手动安装。
- 版本比较仅支持 `主.次.修订` 三段数字，非数字段按 0 处理（沿用 SPEC-017）。

## 实现映射（implemented 后填写）

- `app/src/com/koco/tvremote/Updater.java`：双源探测、候选合并、下载回退。
- `server/src/com/koco/tvremoteserver/Updater.java`：同上，资产名为盒子端 APK。

## 测试映射

- 手工清单：`tests/manual/SPEC-030-ota-dual-source.md`。
- 协议回归不涉及（OTA 使用外部 HTTP 接口，不进 MockBoxServer）。
