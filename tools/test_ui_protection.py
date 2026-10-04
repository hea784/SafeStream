"""UI 与防护改进的设备端综合验证（用 60 秒长片夹具，端口 8094）。

观察通道的坑（本测试踩过并已绕开）：
- uiautomator dump 需要界面 idle：播放中的视频 + 每 500ms 刷新的进度条
  会让它永远拿不到 idle（ERROR: could not get idle state），且失败时不
  写文件，cat 到的是上一轮的陈旧数据。所以播放中的点按用 dumpsys 的
  视图层级坐标（无 idle 要求），uiautomator 断言只在暂停态做（暂停后
  进度条停更，界面恢复 idle）。
- dump 前先删旧文件，防止拿陈旧数据做出假判定。

覆盖：
1. 跟踪参数剥离 + 防护面板（剥离事件、计数标题）
2. 迷你播放器暂停/继续：暂停态 desc 翻转 + 继续后留下非零进度
3. 浮层标记：当前集「播放中」，切集后上一集「看过」

连播回归由 tools/test_autonext.py（6 秒夹具）单独覆盖。
"""
import hashlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

from test_episodes import PKG, URL as _UNUSED, adb, find, tap

URL = "http://127.0.0.1:8094/index.html?utm_source=test&fbclid=abc"
PREFS = "/data/data/%s/shared_prefs/safestream.xml" % PKG


def ui_raw() -> str:
    adb("shell", "rm", "-f", "/sdcard/up.xml")
    adb("shell", "uiautomator", "dump", "/sdcard/up.xml")
    return adb("shell", "cat", "/sdcard/up.xml")


def find_desc(value: str):
    try:
        tree = ET.fromstring(ui_raw())
    except ET.ParseError:
        return None
    for n in tree.iter("node"):
        if n.get("content-desc") == value:
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
            if m:
                x1, y1, x2, y2 = (int(g) for g in m.groups())
                return ((x1 + x2) // 2, (y1 + y2) // 2)
    return None


def dumpsys_center(res_id: str):
    """从 dumpsys 视图树取 view 中心的绝对坐标（frame 相对父视图，沿层级累加）。"""
    out = adb("shell", "dumpsys", "activity", "top")
    stack = []
    for line in out.splitlines():
        m = re.search(r"^(\s*)[\w.$]+\{[^}]*?(\d+),(\d+)-(\d+),(\d+)[^}]*\}", line)
        if not m:
            continue
        indent = len(m.group(1))
        l, t, r, b = map(int, m.group(2, 3, 4, 5))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        base_l, base_t = (stack[-1][1], stack[-1][2]) if stack else (0, 0)
        al, at, ar, ab = base_l + l, base_t + t, base_l + r, base_t + b
        stack.append((indent, al, at))
        if ("app:id/" + res_id) in line:
            return ((al + ar) // 2, (at + ab) // 2)
    return None


def prefs_value(url: str):
    key = "p_" + hashlib.sha256(url.encode()).hexdigest()
    m = re.search(key + r'" value="(-?\d+)"', adb("shell", "cat", PREFS))
    return int(m.group(1)) if m else None


def wait_desc(value: str, seconds: int) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if find_desc(value):
            return True
        time.sleep(1)
    return False


def main():
    adb("root")
    time.sleep(3)
    adb("reverse", "tcp:8094", "tcp:8094")
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)
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
    time.sleep(14)
    adb("shell", "input", "keyevent", "111")  # 收起键盘，稳定布局
    time.sleep(2)

    # 1+2. 防护面板（此刻无视频播放，dump 可靠）
    if not find(res_id="fabPlaylist"):
        print("FAIL：选集按钮未出现（页面没加载好）")
        return 1
    tap(find(res_id="shieldButton"))
    time.sleep(2)
    raw = ui_raw()
    ok_strip = "已剥离跟踪参数" in raw
    ok_title = "防护 · 本次已处理" in raw
    print("面板出现剥离事件:", ok_strip, "| 标题带计数:", ok_title)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(2)
    if not (ok_strip and ok_title):
        print("FAIL：防护面板内容不符合预期")
        return 1

    # 3+4. 点第 1 集 -> 暂停（进入可 dump 状态）-> 浮层标记 -> 切第 2 集
    tap(find(res_id="fabPlaylist"))
    time.sleep(2)
    row = find(text="01")
    if not row:
        print("FAIL：浮层里没有第 1 集")
        return 1
    tap(row)
    print("已点第 1 集，等媒体起播 ...")
    time.sleep(14)
    toggle = dumpsys_center("miniToggle")
    if not toggle:
        print("FAIL：dumpsys 找不到 miniToggle（迷你条没出现？）")
        return 1
    tap(toggle)  # 暂停 ep1：此后界面 idle，uiautomator 恢复可靠
    time.sleep(2)
    ok_paused = find_desc("播放") is not None
    print("暂停后按钮变为「播放」:", ok_paused)
    if not ok_paused:
        print("FAIL：暂停未生效")
        return 1

    tap(dumpsys_center("fabPlaylist"))  # FAB 在活动层级里，dumpsys 坐标可点
    time.sleep(2)
    ok_playing1 = "播放中" in ui_raw()
    print("第 1 集标记「播放中」:", ok_playing1)
    row2 = find(text="02")
    if not row2:
        print("FAIL：浮层里没有第 2 集")
        return 1
    tap(row2)  # 切第 2 集：浮层收起，ep2 自动起播
    time.sleep(14)
    toggle = dumpsys_center("miniToggle")
    if not toggle:
        print("FAIL：切集后 miniToggle 消失")
        return 1
    tap(toggle)  # 暂停 ep2
    time.sleep(2)
    if not find_desc("播放"):
        print("FAIL：第 2 集未暂停（可能没播起来）")
        return 1
    tap(dumpsys_center("miniToggle"))  # 继续播 ep2
    time.sleep(3)
    ep2 = prefs_value("http://127.0.0.1:8094/ep-2/clip1.mp4")
    ok_progress = ep2 is not None and ep2 > 0
    print("第 2 集继续后留下非零进度:", ok_progress, "(value=%s)" % ep2)
    if not ok_progress:
        print("FAIL：暂停/继续未留下非零进度")
        return 1
    tap(dumpsys_center("miniToggle"))  # 再暂停，恢复可 dump 状态
    time.sleep(2)
    tap(dumpsys_center("fabPlaylist"))
    time.sleep(2)
    raw = ui_raw()
    ok_watched = "看过" in raw
    ok_playing2 = "播放中" in raw
    with open(r"D:\tmp_dl\ui_protection.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("切到第 2 集后：01=看过:", ok_watched, "| 02=播放中:", ok_playing2)
    all_ok = ok_playing1 and ok_watched and ok_playing2
    print("判定:", "全部通过" if all_ok else "有项不符合预期")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
