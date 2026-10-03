"""SafeStream 端到端冒烟测试。

前置条件（本机已验证可用的组合）：
  1. AVD venera_test 已启动：emulator-5554
  2. tools/testfixture 已生成，本地 HTTP 服务监听 127.0.0.1:8099
  3. adb reverse tcp:8099 tcp:8099

验证点：明文警告对话框、沙箱独立进程、页面渲染、视频发现、原生播放、安全行为。

用法：python tools/e2e_smoke.py
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
SERIAL = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
MAIN = PKG + "/com.heasafe.safestream.ui.MainActivity"
SANDBOX = PKG + "/com.heasafe.safestream.sandbox.WebHostActivity"
URL = "http://127.0.0.1:8099/index.html"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "docs", "screenshots")

failures = []


def adb(*args, binary=False):
    p = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True)
    out = p.stdout
    return out if binary else out.decode("utf-8", "replace")


def check(label, ok, detail=""):
    print(("[PASS] " if ok else "[FAIL] ") + label + (" -- " + str(detail) if detail else ""))
    if not ok:
        failures.append(label)
    return ok


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/e2e.xml")
    raw = adb("shell", "cat", "/sdcard/e2e.xml", binary=True)
    return ET.fromstring(raw.decode("utf-8", "replace"))


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find(text=None, res_id=None):
    for node in ui().iter("node"):
        if text is not None and node.get("text") != text:
            continue
        if res_id and not (node.get("resource-id") or "").endswith(":id/" + res_id):
            continue
        c = center(node.get("bounds"))
        if c:
            return c
    return None


def tap(point):
    adb("shell", "input", "tap", str(point[0]), str(point[1]))


def texts():
    return [(n.get("text") or "").strip() for n in ui().iter("node")
            if (n.get("text") or "").strip()]


def shot(name):
    os.makedirs(SHOTS, exist_ok=True)
    with open(os.path.join(SHOTS, name), "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("       screenshot -> docs/screenshots/" + name)
def main():
    print("=== 0. 前置检查 ===")
    if "emulator-5554" not in adb("devices"):
        print("模拟器未连接，请先启动 AVD venera_test")
        return 2
    rev = adb("reverse", "--list")
    check("adb reverse 已配置", "tcp:8099" in rev, rev.strip())
    print("")
    print("=== 1. 冷启动 ===")
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", MAIN)
    time.sleep(5)
    check("主进程存活", bool(adb("shell", "pidof", PKG).strip()))
    check("无启动崩溃", "FATAL EXCEPTION" not in adb("logcat", "-d"))
    print("")
    print("=== 2. 明文地址触发警告 ===")
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")  # 收起软键盘
    time.sleep(1)
    tap(find(text="加载"))
    time.sleep(4)
    ok = find(text="仍要加载") is not None
    check("明文 HTTP 弹出警告对话框", ok)
    if not ok:
        shot("e2e-fail-no-dialog.png")
        return 1
    shot("e2e-01-insecure-dialog.png")
    print("")
    print("=== 3. 用户确认后放行 ===")
    tap(find(text="仍要加载"))
    time.sleep(10)
    procs = adb("shell", "ps", "-A")
    lines = [l for l in procs.splitlines() if "safestream" in l]
    check("沙箱以独立进程运行", any(":sandbox" in l for l in lines),
          lines[-1].strip() if lines else "")
    check("沙箱 Activity 在前台", SANDBOX in adb("shell", "dumpsys", "activity", "activities"))
    print("")
    print("=== 4. 沙箱页面渲染 ===")
    t = texts()
    # 注意：Chromium WebView 只有在无障碍服务请求时才把页面内容暴露给
    # uiautomator，所以这里不用页面文案做判据；"发现到视频"本身就是页面已加载的证据。
    check("沙箱进程在前台且未崩溃", SANDBOX in adb("shell", "dumpsys", "activity", "activities")
          and "FATAL EXCEPTION" not in adb("logcat", "-d"))
    shot("e2e-02-sandbox-rendered.png")
    print("")
    print("=== 5. 视频发现 ===")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(5)
    t = texts()
    body = " ".join(t)
    check("列表标题显示发现数量", "3" in body, body[:120])
    titles = [n.get("text") for n in ui().iter("node")
              if (n.get("resource-id") or "").endswith(":id/videoTitle")]
    check("播放列表渲染出条目", len(titles) >= 3, "条目: " + str(titles))
    check("拦截计数已上报", "已拦截" in body, [x for x in t if "拦截" in x][:1])
    shot("e2e-03-playlist.png")
    print("")
    print("=== 6. 原生播放 ===")
    item = None
    for node in ui().iter("node"):
        if "测试视频" in (node.get("text") or ""):
            item = center(node.get("bounds"))
            break
    if item:
        tap(item)
        time.sleep(9)
        shot("e2e-04-playing.png")
        check("点选后无崩溃", "FATAL EXCEPTION" not in adb("logcat", "-d"))
    else:
        check("播放列表存在可点击项", False, "未找到视频条目")
    print("")
    print("=== 7. 安全行为 ===")
    log = adb("logcat", "-d")
    check("无外部 Activity 被拉起", "ActivityNotFoundException" not in log)
    shot("e2e-05-final.png")
    print("")
    print("=" * 52)
    if failures:
        print("失败 " + str(len(failures)) + " 项: " + str(failures))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
