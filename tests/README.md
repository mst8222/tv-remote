# tests — 测试体系

与 spec 一一对应（见 spec/README.md「与测试的对应关系」）。

## 目录

```
tests/
├── protocol/    # 自动测试：直连协议一致性（纯 Java，无第三方依赖）
│   ├── MockBoxServer.java   # 盒子端协议参照实现（从 ServerService 迁移）
│   ├── ProtocolTest.java    # 测试本体（被测对象 = 真实的 LanClient.java）
│   └── run.ps1              # 一键编译 + 运行（JDK 11+）
└── manual/      # 手工验收清单：tests/manual/SPEC-XXX-<短名>.md
```

## 运行自动测试

```powershell
powershell -File tests\protocol\run.ps1
```

全部通过输出 `通过 52，失败 0`，退出码 0；任何失败退出码 1。
要求本机 8898 端口空闲（UDP 发现用例需要）。

## 约定

- 改直连协议（两端任何一端）必须先改 spec，再同步 MockBoxServer 与真实实现，
  然后跑 run.ps1 回归。
- 每个新增 spec 必须有对应测试文件：能自动化的进 protocol/，纯设备交互的进 manual/。
- planned 状态的 spec 允许先把 manual 清单写好（标记「待实现」），
  spec 转 implemented 前清单必须逐条执行并填写结果。
- 手工清单标记：P 通过 / F 失败 / N 未执行；执行后必须填写「实际」与日期。
