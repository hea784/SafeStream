"""决定性验证：点 file:// 之后页面到底有没有被跳走。

背景：安全契约测试里 file/content/javascript 三项始终没有拦截日志，
而截图显示 fixture 页面已经变空白 —— 疑似导航未被拦下。

做法：重新加载 fixture -> 只点 file:// -> 立刻看日志与画面。

用法：python tools/diag_file_nav.py
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
URL = "http://127.0.0.1:8095/index.html"


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def find(res_id, text=None):
    adb("shell", "uiautomator", "dump", "/sdcard/fn.xml")
    raw = adb("shell", "cat", "/sdcard/fn.xml", binary=True)
    root = ET.fromstring(raw.decode("utf-8", "replace"))
    for n in root.iter("node"):
        if text is not None and n.get("text") != text:
            continue
        if res_id and not (n.get("resource-id") or "").endswith(":id/" + res_id):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = (int(g) for g in m.groups())
            return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def load():
    adb("shell", "am", "force-stop", PKG)
    time.sleep(2)
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    p = find("urlInput")
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    d = find(None, "仍要加载")
    if d:
        adb("shell", "input", "tap", str(d[0]), str(d[1]))
    time.sleep(12)


def main():
    load()
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(1)
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.sandbox.WebHostActivity"
        " -e url " + URL)
    time.sleep(12)
    print("=== 加载后先截图（应能看到 file 链接）===")
    with open(r"D:\tmp_dl\fn_before.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    adb("logcat", "-c")
    print("=== 点击 file:// (34,492) ===")
    adb("shell", "input", "tap", "34", "492")
    time.sleep(5)
    log = adb("logcat", "-d", "-s", "SafeStreamSecurity:I")
    print("拦截日志:", "有" if "已阻止跳转" in log else "无")
    with open(r"D:\tmp_dl\fn_after.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("截图: fn_before.png / fn_after.png")
    print("")
    print("判读：若 after 是空白而 before 有内容，说明 file:// 导航未被拦下 —— 这是真漏洞。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
