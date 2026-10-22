# SPEC-017 手工验收清单：OTA 更新

前置说明：应用内更新的入口从 v1.4.0 起才有，v1.3.0 无法自升级。因此验收用
「v1.4.0 → v1.4.1」做真实自升级。GitHub 公开仓库 `mst8222/tv-remote` 的
Release `v1.4.1` 附件含 `TVRemote.apk` 与 `TVRemoteServer.apk`。

## 手机端（API 36 模拟器，英文界面）

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| M-1 | 安装 v1.4.0，打开 App，滚到底部「关于」卡 | 显示「Current version v1.4.0」与「Check for updates」 | 通过 |
| M-2 | 点「Check for updates」 | 弹出「New version v1.4.1」，展示截断后的 Release 说明 | 通过 |
| M-3 | 点「Download and install」 | 完成后出现系统安装确认界面 | 通过 |
| M-4 | 在系统界面确认安装 | 安装完成，`versionName=1.4.1`、`versionCode=5` | 通过 |
| M-5 | 再次点「Check for updates」 | 提示已是最新（v1.4.1 无下载入口） | 通过 |
| M-6 | 网络不可用（全局代理指向 127.0.0.1:9）后点「Check for updates」 | 弹出更新失败提示；遥控界面与直连不受影响 | 通过（观察到 Toast 弹出，未进入更新弹窗） |

## 盒子端（三星 Fold3，Android 13；H96 Max V56 有环境限制）

| 编号 | 步骤 | 预期 | 结果 |
|---|---|---|---|
| M-7 | 安装 v1.4.0，进入 App，滚动到「版本更新」卡 | 显示「当前版本 v1.4.0」，「检查更新」可聚焦 | 通过（Fold3）；H96 卡片显示与焦点导航正常 |
| M-8 | 点「检查更新」 | 卡内显示「发现新版本 v1.4.1（当前 v1.4.0）」，按钮变「下载并安装」 | 通过（Fold3） |
| M-9 | 点「下载并安装」（未授权安装未知应用） | 提示授权并跳到「安装未知应用」设置页 | 通过（Fold3） |
| M-10 | 授权后重新下载，在系统安装界面确认 | 升级到 v1.4.1；`ServerService` 以 `PACKAGE_REPLACED` 自动恢复 | 通过（Fold3，`lastUpdateTime` 更新、`versionName=1.4.1`） |
| M-11 | 再次检查更新 | 显示「已是最新版本 v1.4.1」 | 通过（Fold3） |
| M-12 | 网络不可用时检查更新 | 状态文字显示「检查更新失败：网络请求失败，请检查网络后重试」 | 通过（Fold3/H96 实测） |
| M-13 | H96 上验证按键触发 | 遥控 OK 触发「检查更新」 | 未通过（环境限制）：H96 系统层吞掉 ADB 注入的点击与 OK/ENTER，系统设置界面同样无响应，无法自动化验证；实体遥控未测 |

## 自动项

| 编号 | 命令 | 预期 | 结果 |
|---|---|---|---|
| A-1 | `python build.py app` / `python build.py server` | 均成功，产物 versionName 1.4.1 / versionCode 5 | 通过 |
| A-2 | `powershell -File tests\protocol\run.ps1` | 通过 52，失败 0 | 通过 |
