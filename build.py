#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手动编译 Android APK（不需要 Gradle）
流程：aapt2 link → javac → d8 → 打包 dex → zipalign → apksigner
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = sys.argv[1] if len(sys.argv) > 1 else "app"
APP = os.path.join(HERE, MODULE)
# SPEC-038：盒端每次构建产出正常版（API 26）与低版本版（API 22）；手机端仍只出 API 26
OUTPUTS = {
    "app": [("TVRemote.apk", 26)],
    "server": [("TVRemoteServer.apk", 26), ("TVRemoteServer-android5.apk", 22)],
}
VARIANTS = OUTPUTS.get(MODULE, [(MODULE + ".apk", 26)])
# SPEC-036：手机端内置 ZXing core 用于扫码，编译与 dex 都要带上这些 jar
LIBS = sorted(glob.glob(os.path.join(APP, "libs", "*.jar")))

SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "36.1.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
D8 = os.path.join(BT, "d8.bat")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
KEYSTORE = os.path.join(HERE, "keys", "release.jks")
KS_PASS = "koco123456"
DIST = os.path.join(HERE, "dist")
WEB_DIR = os.path.join(APP, "assets", "web")
WEB_CERT = os.path.join(HERE, "keys", "web.p12")
WEB_PASS = "koco123456"
os.makedirs(DIST, exist_ok=True)


def run(cmd, **kw):
    print("  $", " ".join(str(c) for c in cmd)[:150])
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="ignore", **kw)
    if r.returncode != 0:
        print("STDOUT:", (r.stdout or "")[:2000])
        print("STDERR:", (r.stderr or "")[:3000])
        raise SystemExit(f"失败: {cmd[0]}")
    if r.stdout and r.stdout.strip():
        print("   ", r.stdout.strip()[:600])
    return r


def compile_resources():
    """编译模块 res 目录为 flat 产物；res 不存在时跳过（两模块可独立无资源）"""
    resdir = os.path.join(APP, "res")
    if not os.path.isdir(resdir):
        return []
    flat = os.path.join(APP, "res.zip")
    if os.path.exists(flat):
        os.remove(flat)
    run([AAPT2, "compile", "--dir", resdir, "-o", flat])
    print(f"    资源已编译 ({resdir})")
    return [flat]


def web_assets_dir():
    """SPEC-020：校验静态页、生成/复用自签证书，返回 aapt2 的 assets 目录"""
    if MODULE != "server":
        return None
    if not os.path.isdir(WEB_DIR):
        raise SystemExit(f"[x] 缺少静态页目录: {WEB_DIR}")
    required = ["index.html", "app.js", "style.css", "manifest.webmanifest",
                "sw.js", "icon-192.png", "icon-512.png"]
    for name in required:
        if not os.path.exists(os.path.join(WEB_DIR, name)):
            raise SystemExit(f"[x] 缺少静态页文件: {os.path.join(WEB_DIR, name)}")
    if not os.path.exists(WEB_CERT):
        os.makedirs(os.path.dirname(WEB_CERT), exist_ok=True)
        # -J-Dkeystore.pkcs12.legacy：JDK 高版本默认生成 AES/HmacPBESHA256 的 PKCS12，
        # 盒端 BouncyCastle 无法解析（报 No installed provider supports this key）
        run(["keytool", "-J-Dkeystore.pkcs12.legacy", "-genkeypair",
             "-keystore", WEB_CERT, "-storetype", "PKCS12",
             "-alias", "web", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3650",
             "-storepass", WEB_PASS, "-keypass", WEB_PASS,
             "-dname", "CN=TV Remote Box, OU=Personal, O=Koco, C=CN"])
        print("    已生成 Web 自签证书")
    shutil.copy(WEB_CERT, os.path.join(WEB_DIR, "web.p12"))
    return os.path.join(APP, "assets")


def manifest_for(min_sdk):
    """按变体返回清单路径：minSdk 与清单一致时用原清单，否则复制并替换后再交给 aapt2。

    aapt2 的 --min-sdk-version 只在清单没有 uses-sdk 时生效，所以双版本必须写临时清单。
    """
    src = os.path.join(APP, "AndroidManifest.xml")
    with open(src, encoding="utf-8") as f:
        txt = f.read()
    m = re.search(r'android:minSdkVersion="(\d+)"', txt)
    if not m or int(m.group(1)) == min_sdk:
        return src
    builddir = os.path.join(HERE, ".build")
    os.makedirs(builddir, exist_ok=True)
    dst = os.path.join(builddir, "AndroidManifest-%d.xml" % min_sdk)
    with open(dst, "w", encoding="utf-8") as f:
        f.write(re.sub(r'android:minSdkVersion="\d+"',
                       'android:minSdkVersion="%d"' % min_sdk, txt))
    return dst


def link_resources(extra, min_sdk):
    base = os.path.join(HERE, "base.apk")
    if os.path.exists(base):
        os.remove(base)
    # --java 输出 R.java，供 javac 编译引用资源
    gendir = os.path.join(HERE, "gen", MODULE)
    os.makedirs(gendir, exist_ok=True)
    assets = web_assets_dir()
    cmd = [AAPT2, "link", "-o", base, "-I", ANDROID_JAR,
           "--manifest", manifest_for(min_sdk),
           "--java", gendir,
           "--min-sdk-version", str(min_sdk), "--target-sdk-version", "30",
           "--version-code", "23", "--version-name", "1.8.0"]
    if assets:
        cmd += ["-A", assets]
    run(cmd + extra)
    print("[2/7] 资源与清单已链接 (minSdk %d)" % min_sdk)
    return base


def compile_java():
    cls = os.path.join(HERE, "classes")
    if os.path.exists(cls):
        shutil.rmtree(cls)
    os.makedirs(cls)
    srcs = []
    for root, _, files in os.walk(os.path.join(APP, "src")):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    # 加入 aapt2 生成的 R.java
    for root, _, files in os.walk(os.path.join(HERE, "gen", MODULE)):
        for f in files:
            if f.endswith(".java"):
                srcs.append(os.path.join(root, f))
    stub = os.path.join(BT, "core-lambda-stubs.jar")
    bootcp = ANDROID_JAR + ";" + stub if os.path.exists(stub) else ANDROID_JAR
    cp = ";".join([ANDROID_JAR] + LIBS)
    run(["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", bootcp, "-classpath", cp,
         "-nowarn", "-d", cls] + srcs)
    print(f"[3/7] javac 编译完成 ({len(srcs)} 个源文件)")


def dex(min_sdk):
    cls = os.path.join(HERE, "classes")
    dexdir = os.path.join(HERE, "dexout")
    if os.path.exists(dexdir):
        shutil.rmtree(dexdir)
    os.makedirs(dexdir)
    classfiles = []
    for root, _, files in os.walk(cls):
        for f in files:
            if f.endswith(".class"):
                classfiles.append(os.path.join(root, f))
    # 预编译 jar（如 ZXing）不在 classes/ 里，必须直接作为 d8 输入才会打进 APK
    run([D8, "--lib", ANDROID_JAR, "--min-api", str(min_sdk),
         "--output", dexdir] + classfiles + LIBS)
    print("[4/7] d8 生成 dex")


def pack(base):
    out = os.path.join(HERE, "unsigned.apk")
    if os.path.exists(out):
        os.remove(out)
    shutil.copy(base, out)
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(HERE, "dexout", "classes.dex"), "classes.dex")
    print("[5/7] classes.dex 已打包")


def align_sign(out_name):
    aligned = os.path.join(HERE, "aligned.apk")
    if os.path.exists(aligned):
        os.remove(aligned)
    run([ZIPALIGN, "-f", "4", os.path.join(HERE, "unsigned.apk"), aligned])

    if not os.path.exists(KEYSTORE):
        run(["keytool", "-genkeypair", "-keystore", KEYSTORE, "-alias", "koco",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", KS_PASS, "-keypass", KS_PASS,
             "-dname", "CN=Koco Remote, OU=Personal, O=Koco, C=CN"])
        print("    已生成签名密钥库")

    out = os.path.join(DIST, out_name)
    if os.path.exists(out):
        os.remove(out)
    run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", "koco",
         "--out", out, aligned])
    print("[6/7] zipalign + 签名完成")
    return out


def verify(out, min_sdk):
    run([APKSIGNER, "verify", "--print-certs", out])
    r = subprocess.run([os.path.join(BT, "aapt2.exe"), "dump", "badging", out],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    line = ""
    for l in (r.stdout or "").splitlines():
        if l.startswith("package:") or l.startswith("minSdkVersion:"):
            print("   ", l)
            line += l
    if "minSdkVersion:'%d'" % min_sdk not in line:
        raise SystemExit("[x] %s 的 minSdk 不是 %d" % (out, min_sdk))
    print(f"[7/7] 产物: {out}  {os.path.getsize(out)/1024:.0f} KB")


if __name__ == "__main__":
    for p, n in [(AAPT2, "aapt2"), (D8, "d8"), (ZIPALIGN, "zipalign"),
                 (APKSIGNER, "apksigner"), (ANDROID_JAR, "android.jar")]:
        if not os.path.exists(p):
            print(f"[x] 缺少 {n}: {p}")
            sys.exit(1)
    res = compile_resources()
    for out_name, min_sdk in VARIANTS:
        print(f"===== {out_name} (minSdk {min_sdk}) =====")
        base = link_resources(res, min_sdk)
        compile_java()
        dex(min_sdk)
        pack(base)
        out = align_sign(out_name)
        verify(out, min_sdk)
