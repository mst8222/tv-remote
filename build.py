#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手动编译 Android APK（不需要 Gradle）
流程：生成密钥常量 → aapt2 link → javac → d8 → 打包 dex → zipalign → apksigner
"""
import os
import shutil
import subprocess
import sys
import zipfile

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = sys.argv[1] if len(sys.argv) > 1 else "app"
APP = os.path.join(HERE, MODULE)
OUT_NAMES = {"app": "TVRemote.apk", "server": "TVRemoteServer.apk"}
SDK = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
BT = os.path.join(SDK, "build-tools", "36.1.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")
AAPT2 = os.path.join(BT, "aapt2.exe")
D8 = os.path.join(BT, "d8.bat")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
APKSIGNER = os.path.join(BT, "apksigner.bat")
KEYSTORE = os.path.join(HERE, "keys", "release.jks")
KS_PASS = "koco123456"
OUT = os.path.join(HERE, "dist", OUT_NAMES.get(MODULE, MODULE + ".apk"))
os.makedirs(os.path.dirname(OUT), exist_ok=True)


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


def gen_key_const():
    import base64 as b64mod
    pk8 = os.path.join(HERE, "keys", "adbkey.pk8")
    b64 = "".join(open(pk8, "r", encoding="utf-8").read().split())
    cert_der = os.path.join(HERE, "keys", "cert.der")
    cert_b64 = ""
    if os.path.exists(cert_der):
        cert_b64 = b64mod.b64encode(open(cert_der, "rb").read()).decode()
    src = os.path.join(APP, "src", "com", "koco", "tvremote", "KeyConst.java")
    with open(src, "w", encoding="utf-8") as f:
        f.write("package com.koco.tvremote;\n\n")
        f.write("public final class KeyConst {\n")
        f.write('    public static final String PRIV =\n')
        lines = [b64[i:i + 76] for i in range(0, len(b64), 76)]
        f.write('        "' + '\\n" +\n        "'.join(lines) + '";\n')
        f.write('\n    public static final String CERT =\n')
        clines = [cert_b64[i:i + 76] for i in range(0, len(cert_b64), 76)]
        f.write('        "' + '\\n" +\n        "'.join(clines) + '";\n')
        f.write("}\n")
    print(f"[1/7] 密钥常量已生成 (key {len(lines)} 行, cert {len(clines)} 行)")


def link_resources():
    base = os.path.join(HERE, "base.apk")
    if os.path.exists(base):
        os.remove(base)
    run([AAPT2, "link", "-o", base, "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "--min-sdk-version", "26", "--target-sdk-version", "30",
         "--version-code", "1", "--version-name", "1.0"])
    print("[2/7] 资源与清单已链接")
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
    stub = os.path.join(BT, "core-lambda-stubs.jar")
    bootcp = ANDROID_JAR + ";" + stub if os.path.exists(stub) else ANDROID_JAR
    run(["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
         "-bootclasspath", bootcp, "-classpath", ANDROID_JAR,
         "-nowarn", "-d", cls] + srcs)
    print(f"[3/7] javac 编译完成 ({len(srcs)} 个源文件)")


def dex():
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
    run([D8, "--lib", ANDROID_JAR, "--min-api", "26", "--output", dexdir] + classfiles)
    print("[4/7] d8 生成 dex")


def pack(base):
    out = os.path.join(HERE, "unsigned.apk")
    if os.path.exists(out):
        os.remove(out)
    shutil.copy(base, out)
    with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(HERE, "dexout", "classes.dex"), "classes.dex")
    print("[5/7] classes.dex 已打包")


def align_sign():
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

    if os.path.exists(OUT):
        os.remove(OUT)
    run([APKSIGNER, "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", "koco",
         "--out", OUT, aligned])
    print("[6/7] zipalign + 签名完成")


def verify():
    run([APKSIGNER, "verify", "--print-certs", OUT])
    r = subprocess.run([os.path.join(BT, "aapt2.exe"), "dump", "badging", OUT],
                       capture_output=True, text=True, encoding="utf-8", errors="ignore")
    for line in (r.stdout or "").splitlines()[:3]:
        print("   ", line)
    print(f"[7/7] 产物: {OUT}  {os.path.getsize(OUT)/1024:.0f} KB")


if __name__ == "__main__":
    if MODULE == "app" and not os.path.exists(os.path.join(HERE, "keys", "adbkey.pk8")):
        print("[x] 缺少 keys/adbkey.pk8（设备 ADB 授权用的私钥，不入库）")
        print("    请从备份恢复；或重新生成后，把新公钥写入盒子 /data/misc/adb/adb_keys：")
        print("      cd tools && javac AdbTest.java && java AdbTest gen")
        sys.exit(1)
    if MODULE == "server" and not os.path.exists(os.path.join(HERE, "keys", "adbkey.pk8")):
        pass  # server 模块不需要 ADB 私钥
    for p, n in [(AAPT2, "aapt2"), (D8, "d8"), (ZIPALIGN, "zipalign"),
                 (APKSIGNER, "apksigner"), (ANDROID_JAR, "android.jar")]:
        if not os.path.exists(p):
            print(f"[x] 缺少 {n}: {p}")
            sys.exit(1)
    if MODULE == "app":
        gen_key_const()
    base = link_resources()
    compile_java()
    dex()
    pack(base)
    align_sign()
    verify()
