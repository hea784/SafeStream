"""确认 file:// / content:// 点击后到底有没有被拦。

背景：安全契约回归测试里 intent、market 有"已阻止跳转"日志，
但 file、content 没有任何日志。两种可能：
  A. 点击没落到链接上（测试问题）
  B. shouldOverrideUrlLoading 对非 http(s) 的 scheme 不触发（真漏洞）

做法：重新加载页面 -> 截图 -> 逐个点击 -> 记录日志与页面是否变化。

用法：python tools/diag_scheme.py
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

ITEMS = [("file", 34, 492), ("content", 77, 492),
         ("intent", 128, 492), ("market", 182, 492), ("javascript", 236, 492)]


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def find(res_id):
    adb("shell", "uiautomator", "dump", "/sdcard/ds.xml")
    raw = adb("shell", "cat", "/sdcard/ds.xml", binary=True)
    root = ET.fromstring(raw.decode("utf-8", "replace"))
    for n in root.iter("node"):
        if (n.get("resource-id") or "").endswith(":id/" + res_id):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = (int(g) for g in m.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def load():
    adb("shell", "am", "start", "-n",
        PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    p = find("urlInput")
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    d = find("仍要加载")
    if d:
        adb("shell", "input", "tap", str(d[0]), str(d[1]))
    time.sleep(12)


def main():
    load()
    print("页面已加载，逐个点击并观察：\n")
    for name, x, y in ITEMS:
        adb("logcat", "-c")
        before = adb("shell", "dumpsys", "activity", "activities")
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(4)
        log = adb("logcat", "-d", "-s", "SafeStreamSecurity:I")
        after = adb("shell", "dumpsys", "activity", "activities")
        moved = "WebHostActivity" not in after
        blocked = "已阻止跳转" in log
        print("%-11s 日志=%-5s 离开页面=%s" % (name, blocked, moved))
        if blocked:
            line = [l for l in log.splitlines() if "已阻止跳转" in l]
            print("            " + (line[-1][-60:] if line else ""))
        if moved:
            print("            ★ 页面被跳走了，这条 scheme 没拦住")
            adb("shell", "am", "start", "-n",
                PKG + "/com.heasafe.safestream.ui.MainActivity")
            time.sleep(3)
            load()
    return 0


if __name__ == "__main__":
    sys.exit(main())
