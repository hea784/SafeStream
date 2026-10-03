"""验证选集识别：夹具页有 4 集，播放列表应出现 4 个剧集条目。

夹具的 DOM 结构取自真实站点（akep5.xxpofweu.cc 的 hg-web-play__ep）。

前置：夹具服务 8096 + adb reverse tcp:8096 tcp:8096

用法：python tools/test_episodes.py
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
URL = "http://127.0.0.1:8096/index.html"


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/ep.xml")
    raw = adb("shell", "cat", "/sdcard/ep.xml", binary=True)
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


def titles():
    return [n.get("text") or "" for n in ui().iter("node")
            if (n.get("resource-id") or "").endswith(":id/videoTitle")]


def metas():
    return [n.get("text") or "" for n in ui().iter("node")
            if (n.get("resource-id") or "").endswith(":id/videoMeta")]


def tap(point):
    adb("shell", "input", "tap", str(point[0]), str(point[1]))


def main():
    if "tcp:8096" not in adb("reverse", "--list"):
        print("缺少 adb reverse tcp:8096 tcp:8096")
        return 2
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    # 新版没有"加载"按钮了，搜索栏回车即提交
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    d = find(text="仍要加载")
    if d:
        tap(d)
    time.sleep(16)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(5)

    # RecyclerView 只渲染可见项，要滚动才能数全
    seen = []
    for _ in range(6):
        for t in titles():
            if t not in seen:
                seen.append(t)
        adb("shell", "input", "swipe", "540", "1600", "540", "1100", "300")
        time.sleep(1)
    ms = metas()
    ep_count = sum(1 for m in ms if "整集" in m)
    print("滚动后看到的条目:", seen)
    print("当前可见标注:", ms)
    print("识别到的集数:", ep_count)
    ok = ep_count >= 4
    print("判定:", "选集识别成功" if ok else "选集识别失败")
    with open(r"D:\tmp_dl\ep_test.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
