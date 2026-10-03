"""网络与工具链连通性诊断：开/关 VPN 前后各跑一次，对比输出即可定位问题。

为什么要单独写：开 VPN 后 agent 工具会失败，但断在哪一环并不明显
（可能是某个 API 域名、可能是 adb 连模拟器、可能是 DNS 被改写）。
这个脚本一次性把关键路径全探一遍。

用法：
    python tools/net_diag.py > vpn_off.txt
    # 开 VPN
    python tools/net_diag.py > vpn_on.txt
    fc vpn_off.txt vpn_on.txt
"""
import os
import re
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
JDK = r"D:\dev-tools\sdks\jdk-17.0.20.1+1"
SDK = r"D:\dev-tools\sdks\android-sdk"

HOSTS = [
    ("github.com", "github.com", "https://github.com"),
    ("api.github.com", "api.github.com", "https://api.github.com/rate_limit"),
    ("codeload.gh", "codeload.github.com", "https://codeload.github.com"),
    ("raw.ghusercontent", "raw.githubusercontent.com",
     "https://raw.githubusercontent.com"),
    ("objects.ghusercontent", "objects.githubusercontent.com",
     "https://objects.githubusercontent.com"),
    ("playwright CDN", "playwright.azureedge.net", "https://playwright.azureedge.net"),
    ("npm registry", "registry.npmjs.org", "https://registry.npmjs.org"),
    ("google", "www.google.com", "https://www.google.com/generate_204"),
]

PROXY_PORTS = [7897, 7890, 7892, 10809, 1080]


def dns(name):
    try:
        ips = sorted({a[4][0] for a in socket.getaddrinfo(name, 443, type=socket.SOCK_STREAM)})
        return "DNS ok   " + ", ".join(ips[:3])
    except Exception as exc:
        return "DNS FAIL " + type(exc).__name__


def tcp(host):
    t0 = time.time()
    try:
        with socket.create_connection((host, 443), timeout=6):
            return f"TCP ok    {int((time.time() - t0) * 1000):>5} ms"
    except Exception as exc:
        return "TCP FAIL  " + type(exc).__name__


def http(url):
    t0 = time.time()
    req = urllib.request.Request(url, method="GET")
    req.add_header("User-Agent", "safestream-netdiag")
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            return f"HTTP {resp.status:<4} {int((time.time() - t0) * 1000):>5} ms"
    except urllib.error.HTTPError as exc:
        return f"HTTP {exc.code:<4} {int((time.time() - t0) * 1000):>5} ms"
    except Exception as exc:
        return f"HTTP FAIL  {int((time.time() - t0) * 1000):>5} ms  {type(exc).__name__}"


def listeners():
    out = []
    for port in PROXY_PORTS:
        sock = socket.socket()
        sock.settimeout(0.4)
        try:
            sock.connect(("127.0.0.1", port))
            out.append(f"127.0.0.1:{port} LISTEN")
        except Exception:
            pass
        finally:
            sock.close()
    return out or ["(无本地代理端口监听)"]


def adb_state():
    if not os.path.exists(ADB):
        return ["adb 不存在"]
    try:
        p = subprocess.run([ADB, "devices"], capture_output=True, text=True, timeout=15)
    except Exception as exc:
        return ["adb 调用失败: " + str(exc)[:80]]
    rows = [l for l in p.stdout.splitlines()[1:] if l.strip()]
    return rows or ["无设备连接（模拟器没开，或 adb 受 VPN 影响）"]


def toolchain():
    out = []
    out.append("JDK    " + ("ok" if os.path.isdir(JDK) else "MISSING"))
    out.append("SDK    " + ("ok" if os.path.isdir(SDK) else "MISSING"))
    env = dict(os.environ)
    env["JAVA_HOME"] = JDK
    env["ANDROID_HOME"] = SDK
    env["GRADLE_USER_HOME"] = r"D:\dev-tools\caches\gradle"
    try:
        p = subprocess.run(["gradlew.bat", "--version", "--console=plain", "--offline"],
                           capture_output=True, text=True, timeout=120, env=env)
        m = re.search(r"Gradle (\S+)", p.stdout or "")
        out.append("Gradle " + (m.group(1) if m else "?"))
    except Exception as exc:
        out.append("Gradle FAIL " + str(exc)[:60])
    return out
def main():
    bar = "=" * 64
    print(bar)
    print("SafeStream 网络与工具链诊断   " + time.strftime("%Y-%m-%d %H:%M:%S"))
    print(bar)
    print("")
    print("[1] 本地代理端口")
    for item in listeners():
        print("    " + item)
    print("")
    print("[2] 连通性（DNS / TCP443 / HTTP）")
    for label, host, url in HOSTS:
        print(f"    {label:<22} {dns(host)}")
        print(f"    {'':<22} {tcp(host)}")
        print(f"    {'':<22} {http(url)}")
    print("")
    print("[3] adb / 模拟器")
    for item in adb_state():
        print("    " + item)
    print("")
    print("[4] 构建工具链")
    for item in toolchain():
        print("    " + item)
    print("")
    print(bar)
    return 0


if __name__ == "__main__":
    sys.exit(main())
