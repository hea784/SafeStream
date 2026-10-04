"""复现真机上报的崩溃：连续剧里多次选择不同集数后 App 崩溃。

真机日志拿不到，所以在夹具上以相同代码路径复现 ——
夹具的选集 DOM 结构取自真实站点（hg-web-play__ep + data-ep-id）。

做法：反复点不同的集，间隔很短，逼近"连续快速切换"，同时抓 FATAL。

用法：python tools/repro_episode_crash.py [轮数]
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
    adb("shell", "uiautomator", "dump", "/sdcard/rc.xml")
    raw = adb("shell", "cat", "/sdcard/rc.xml", binary=True)
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


def tap(p):
    adb("shell", "input", "tap", str(p[0]), str(p[1]))


def alive():
    return bool(adb("shell", "pidof", PKG).strip())


def first_item_point():
    for n in ui().iter("node"):
        if (n.get("resource-id") or "").endswith(":id/videoTitle"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = (int(g) for g in m.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def main():
    rounds = int(sys.argv[1]) if len(sys.argv) > 1 else 6
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
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(1)
    # 新版没有"加载"按钮，搜索栏回车即提交
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    d = find(text="仍要加载")
    if d:
        tap(d)
    time.sleep(14)
    print("初始 alive =", alive())

    for i in range(rounds):
        tap(find(text="播放"))
        time.sleep(1)
        p = first_item_point()
        if not p:
            print("  第 %d 轮找不到列表条目" % i)
            break
        tap(p)
        time.sleep(1.5)
        ok = alive()
        print("  第 %d 轮点击后 alive = %s" % (i, ok))
        if not ok:
            print(">>> 复现崩溃")
            break

    print("")
    print("=== 崩溃堆栈 ===")
    lines = adb("logcat", "-d", "-v", "brief").splitlines()
    for idx, line in enumerate(lines):
        if "FATAL EXCEPTION" in line:
            print(line)
            for x in lines[idx:idx + 30]:
                if "com.heasafe" in x or "Caused by" in x:
                    print("   " + x.strip())
            break
    else:
        print("（未捕获到 FATAL）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
