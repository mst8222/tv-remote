# SPEC-022 蓝牙 HID 跨端边界与降级引导 — 手工验收清单

对应 spec：`spec/developing/SPEC-022-bt-hid-boundary.md`
执行环境：H96 Max V56（192.168.3.125）、三星 Note20、模拟器；
浏览器覆盖 Android Chrome、iPhone Safari、HarmonyOS NEXT 浏览器。
当前状态：能力边界与降级引导已落地，自动/文档项已执行；多端浏览器与真机待验收。

| 编号 | 操作 | 预期 | 结果 |
| --- | --- | --- | --- |
| AC-1 | 分别用 Android Chrome、iPhone Safari、HarmonyOS NEXT 浏览器打开盒端 Web 页面 | 均无蓝牙入口，只有局域网遥控功能 | 页面已确认无蓝牙按钮/API（Chromium 验证）；其余浏览器待执行 |
| AC-2 | 停止盒端服务后打开或刷新 Web 页面 | 提示包含「Android App 蓝牙模式」与「2.4G 遥控器」两条替代路径 | 待执行（页面离线文案已包含两条路径） |
| AC-3 | 在 API < 28 的模拟器上点击「蓝牙」 | 弹窗含「浏览器访问盒端网页」说明；关闭后其他功能正常 | 待执行（文案已加入 `http://<IP>:17880` 或盒端界面查看地址） |
| AC-4 | Note20 蓝牙连接 H96，验证方向/OK/长按/飞鼠 | 与 SPEC-005、SPEC-018 已记录结论一致，无回归 | 待执行 |
| AC-5 | 检查中英文 README | 均含能力矩阵与 HID 描述符缓存、iOS/鸿蒙仅 Web 的说明 | P（README.md / README.en.md 已加能力矩阵与边界） |
| AC-6 | 运行 `powershell -File tests\protocol\run.ps1`，执行两端构建 | 全部通过；两端构建成功 | P（协议 92 + Web API 38；两端构建成功） |

## 实测备注

（实现并执行后填写：设备与浏览器版本、文案检查位置、异常现象）
