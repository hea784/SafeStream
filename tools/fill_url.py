"""把 URL 准确打进 SafeStream 的地址栏并核对，杜绝手输打错。

用法：python tools/fill_url.py <url> [--load]
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
    adb("shell", "uiautomator", "dump", "/sdcard/f.xml")
    raw = adb("shell", "cat", "/sdcard/f.xml", binary=True)
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


def field_text():
    for n in ui().iter("node"):
        if (n.get("resource-id") or "").endswith(":id/urlInput"):
            return n.get("text") or ""
    return ""


def tap(pt):
    adb("shell", "input", "tap", str(pt[0]), str(pt[1]))


def main():
    url = sys.argv[1]
    do_load = "--load" in sys.argv
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(70):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")
    time.sleep(1)
    adb("shell", "input", "text", url)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    got = field_text()
    print("期望:", url)
    print("实际:", got)
    print("一致:", got.strip() == url.strip())
    if do_load and got.strip() == url.strip():
        tap(find(text="加载"))
        print("已点击加载")
    return 0 if got.strip() == url.strip() else 1


if __name__ == "__main__":
    sys.exit(main())
