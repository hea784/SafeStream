"""验证播放器不会因扫描脚本重复上报而反复重启（表现为画面闪烁）。

闪烁的根因：扫描脚本的 MutationObserver 在广告/懒加载页面上持续上报，
每次上报都重建 MediaItem，导致 ExoPlayer 反复 setMediaItem + prepare。

判据：等页面安静一段时间后，统计 playAt 重建播放的次数。
  1 次 = 正常；>=2 次 = 仍在闪。

前置：夹具服务 127.0.0.1:8099 + adb reverse tcp:8099 tcp:8099

用法：python tools/test_no_flicker.py
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
DEFAULT_URL = "http://127.0.0.1:8097/index.html"


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/nf.xml")
    raw = adb("shell", "cat", "/sdcard/nf.xml", binary=True)
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


def tap(pt):
    adb("shell", "input", "tap", str(pt[0]), str(pt[1]))


def playat_count():
    log = adb("logcat", "-d", "-s", "SafeStream:D")
    return len([l for l in log.splitlines() if "playAt 重建播放" in l])


def main():
    url = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_URL
    port = url.split("//")[1].split(":")[1].split("/")[0]
    if "tcp:" + port not in adb("reverse", "--list"):
        print("缺少 adb reverse tcp:" + port + " tcp:" + port)
        return 2
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "text", url)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    tap(find(text="加载"))
    time.sleep(5)
    d = find(text="仍要加载")
    if d:
        tap(d)
    print("沙箱已开，等待 25 秒让页面充分抖动（广告位+懒加载会持续触发 MutationObserver）")
    time.sleep(25)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(4)
    n = playat_count()
    print("playAt 重建播放次数:", n)
    print("判定:", "无闪烁" if n <= 1 else "仍在反复重启（闪烁）")
    return 0 if n <= 1 else 1


if __name__ == "__main__":
    sys.exit(main())
