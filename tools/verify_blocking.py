"""在真实站点上验证广告/跟踪拦截是否生效。

用法：python tools/verify_blocking.py <url> [name]
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/v.xml")
    raw = adb("shell", "cat", "/sdcard/v.xml", binary=True)
    return ET.fromstring(raw.decode("utf-8", "replace"))


def find(text=None, res_id=None):
    for n in ui().iter("node"):
        if text is not None and n.get("text") != text:
            continue
        if res_id and not (n.get("resource-id") or "").endswith(":id/" + res_id):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = (int(g) for g in m.groups())
            return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def texts():
    return [(n.get("text") or "").strip() for n in ui().iter("node")
            if (n.get("text") or "").strip()]


def main():
    url = sys.argv[1]
    name = sys.argv[2] if len(sys.argv) > 2 else "site"
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    p = find(res_id="urlInput")
    adb("shell", "input", "tap", str(p[0]), str(p[1]))
    time.sleep(2)
    adb("shell", "input", "text", url)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    q = find(text="加载")
    adb("shell", "input", "tap", str(q[0]), str(q[1]))
    time.sleep(25)
    # 回主界面读状态行
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(4)
    t = texts()
    lines = ["URL: " + url] + ["TXT: " + x for x in t]
    with open(rf"D:\tmp_{name}_block.txt", "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines))
    with open(rf"D:\tmp_{name}_block.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("ok")


if __name__ == "__main__":
    main()
