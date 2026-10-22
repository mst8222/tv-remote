# 协议一致性自动测试一键脚本（Windows + JDK 11+）
# 用法：powershell -File tests\protocol\run.ps1
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$build = Join-Path $PSScriptRoot "build"
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force $build | Out-Null

# 被测代码 = 真实的手机端 LanClient + 参照实现 + 测试本体
$sources = @(
    "MockBoxServer.java",
    "ProtocolTest.java",
    "WebApiTest.java",
    "QrCodeTest.java",
    "..\..\app\src\com\koco\tvremote\LanClient.java",
    "..\..\app\src\com\koco\tvremote\TouchSession.java",
    "..\..\server\src\com\koco\tvremoteserver\WebServer.java",
    "..\..\server\src\com\koco\tvremoteserver\QrEncoder.java"
)

javac -encoding UTF-8 -d $build @sources
if ($LASTEXITCODE -ne 0) { Write-Host "编译失败"; exit 1 }

java "-Dfile.encoding=UTF-8" -cp $build ProtocolTest
$protocolExit = $LASTEXITCODE
java "-Dfile.encoding=UTF-8" -cp $build WebApiTest
$webExit = $LASTEXITCODE
java "-Dfile.encoding=UTF-8" -cp $build QrCodeTest
$qrExit = $LASTEXITCODE
if ($protocolExit -ne 0 -or $webExit -ne 0 -or $qrExit -ne 0) { exit 1 }
exit 0
