"""安全契约回归测试：逐条验证防护在设备上真的生效。

为什么需要：架构合并（双进程 -> 单进程）会动到 WebView 所在的 Activity，
合并后必须能证明防护没退化，而不是靠"应该还在"。

判据用 App 自己的状态行：WebHostActivity 每次拦截都会 reportEvent，
状态行里能看到"已阻止下载""已阻止页面弹窗"等字样，比截图判读可靠。

前置：安全夹具服务 8095 + adb reverse tcp:8095 tcp:8095

用法：python tools/test_security.py
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
S = "emulator-5554"
PKG = "com.heasafe.safestream.debug"
URL = "http://127.0.0.1:8095/index.html"
SHOTS = r"D:\tmp_dl"

# 测试页里各攻击项的坐标（1080x2400 实机视口量得；改夹具布局需重量）
ATK = {
    "file": (34, 492),
    "content": (77, 492),
    "intent": (128, 492),
    "market": (182, 492),
    "javascript": (236, 492),
    "geo": (34, 716),
    "cam": (94, 716),
    "popup": (154, 716),
    "filechooser": (212, 716),
    "download": (32, 862),
}

results = []


def adb(*a, binary=False):
    p = subprocess.run([ADB, "-s", S, *a], capture_output=True)
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/sec.xml")
    raw = adb("shell", "cat", "/sdcard/sec.xml", binary=True)
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


def status():
    """读 App 的安全审计日志。

    不用界面状态行：沙箱 Activity 在前台时读不到主界面的控件，
    而逐条点击之间又不能随意切 Activity（会把 WebView 状态清掉）。
    """
    return adb("logcat", "-d", "-s", "SafeStreamSecurity:I")


def clear_log():
    adb("logcat", "-c")


def tap(pt):
    adb("shell", "input", "tap", str(pt[0]), str(pt[1]))


def check(label, ok, detail=""):
    results.append((label, ok))
    print(("[PASS] " if ok else "[FAIL] ") + label + (" -- " + detail if detail else ""))


def load():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", PKG + "/com.heasafe.safestream.ui.MainActivity")
    time.sleep(5)
    tap(find(res_id="urlInput"))
    time.sleep(2)
    adb("shell", "input", "text", URL)
    time.sleep(2)
    adb("shell", "input", "keyevent", "KEYCODE_ENTER")
    time.sleep(5)
    d = find(text="仍要加载")
    if d:
        tap(d)
    time.sleep(10)
    # 广告请求发生在页面加载期间，必须先取这段日志再清空，
    # 否则契约 2 会因为"日志被提前清掉"而假失败。
    globals()["LOAD_LOG"] = adb("logcat", "-d", "-s", "SafeStreamSecurity:I")
    clear_log()


def main():
    os.makedirs(SHOTS, exist_ok=True)
    if "tcp:8095" not in adb("reverse", "--list"):
        print("缺少 adb reverse tcp:8095 tcp:8095")
        return 2
    load()
    print("加载后状态行:", status())

    # 契约 1：危险 scheme 导航必须被拦
    print("")
    print("契约 1 危险 scheme 导航")
    for key in ("file", "content", "intent", "market", "javascript"):
        clear_log()
        tap(ATK[key])
        time.sleep(3)
        st = status()
        check("拦截 " + key, "已阻止跳转" in st, st.strip()[-80:])

    # 契约 2：广告与跟踪请求被网络层拦下（页面上的图片全裂）
    print("")
    print("契约 2 广告与跟踪拦截")
    st = globals().get("LOAD_LOG", "")
    for host in ("googlesyndication", "google-analytics", "cloudflareinsights",
                 "adsdk", "54.169.219.76"):
        check("拦截广告域 " + host, host in st, "")

    # 契约 3：能力请求被拒绝
    print("")
    print("契约 3 能力请求拒绝")
    for key, expect in (("geo", "权限"), ("cam", "权限"), ("popup", "弹窗"),
                        ("filechooser", "文件选择")):
        clear_log()
        tap(ATK[key])
        time.sleep(3)
        st = status()
        check("拒绝 " + key, expect in st, st.strip()[-80:])

    # 契约 4：下载被阻止
    print("")
    print("契约 4 下载拦截")
    clear_log()
    tap(ATK["download"])
    time.sleep(3)
    st = status()
    check("阻止下载", "已阻止下载" in st, st.strip()[-80:])

    # 契约 5：无外部 Activity 被拉起
    log = adb("logcat", "-d")
    check("无外部 Activity 被拉起", "ActivityNotFoundException" not in log)

    with open(os.path.join(SHOTS, "sec_after.png"), "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))

    print("")
    bad = [n for n, ok in results if not ok]
    print("通过 %d/%d" % (len(results) - len(bad), len(results)))
    if bad:
        print("失败: " + ", ".join(bad))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
