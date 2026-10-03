"""诊断夹具页：沙箱里到底发生了什么，桥接有没有通路。

症状是播放列表全空，连网络钩子该抓到的 clip1.mp4 都没回传，
说明问题在注入或桥接，而不是选集逻辑。

用法：python tools/diag_ep.py
"""
import re
import subprocess
import sys
import time

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
URL = "http://127.0.0.1:8096/index.html"


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    out = p.stdout
    return out if binary else out.decode("utf-8", "replace")


def find(text=None, res_id=None):
    adb("shell", "uiautomator", "dump", "/sdcard/g.xml")
    raw = adb("shell", "cat", "/sdcard/g.xml", binary=True)
    for b in re.findall(rb"<node[^>]*>", raw):
        s = b.decode("utf-8", "replace")
        if text is not None and ('text="%s"' % text) not in s:
            continue
        if res_id and (":id/" + res_id) not in s:
            continue
        m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', s)
        if m:
            a, b2, c, d = (int(g) for g in m.groups())
            return ((a + c) // 2, (b2 + d) // 2)
    return None


def tap(p):
    adb("shell", "input", "tap", str(p[0]), str(p[1]))


def main():
    adb("shell", "am", "force-stop", PKG)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(6)
    d = find(text="仍要加载")
    if d:
        print("弹出明文警告，点确认")
        tap(d)
    time.sleep(14)
    top = adb("shell", "dumpsys", "activity", "activities").splitlines()
    for line in top:
        if "topResumedActivity" in line:
            print("前台:", line.strip()[:120])
    with open(r"D:\tmp_dl\diag_sandbox.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("沙箱截图 -> D:\\tmp_dl\\diag_sandbox.png")
    log = adb("logcat", "-d").splitlines()
    keys = ("chromium", "net::", "SafeStream", "safestream", "WebMessage")
    keep = [l for l in log if any(k in l for k in keys)]
    with open(r"D:\tmp_dl\diag_log.txt", "w", encoding="utf-8") as fh:
        fh.write("\n".join(keep[-150:]))
    print("相关日志", len(keep), "条 -> D:\\tmp_dl\\diag_log.txt")
    return 0


if __name__ == "__main__":
    sys.exit(main())
