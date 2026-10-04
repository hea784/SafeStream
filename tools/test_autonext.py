"""验证自动连播：夹具第 1 集（6 秒）播完后，自动连播到第 2、第 3 集。

判定用 prefs 里的进度 key（saveProgress 以媒体 URL 的 SHA-256 为 key），
不用状态行文字——状态行只显示 3.5 秒，而一轮 uiautomator dump 要 3-4 秒，
靠截图抓文字必然漏报。进度 key 是"这一集真的在播"的客观证据。

判定：
1. 60 秒内出现 ep-2 媒体的进度 key —— 1 -> 2 自动连播成立
2. 再 60 秒内出现 ep-3 媒体的进度 key —— 2 -> 3 连续连播成立

前置：无（脚本自己 adb root + 建 reverse 隧道）
用法：python tools/test_autonext.py
"""
import hashlib
import sys
import time

from test_episodes import PKG, URL, adb, find, tap

PREFS = "/data/data/%s/shared_prefs/safestream.xml" % PKG


def media_url(ep: int) -> str:
    return "http://127.0.0.1:8096/ep-%d/clip1.mp4" % ep


def prefs_key(url: str) -> str:
    return "p_" + hashlib.sha256(url.encode()).hexdigest()


def prefs_has(url: str) -> bool:
    return prefs_key(url) in adb("shell", "cat", PREFS)


def wait_for(url: str, seconds: int) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        if prefs_has(url):
            return True
        time.sleep(2)
    return False


def main():
    adb("root")
    time.sleep(3)
    # adb root 会重启 adbd，把先前建立的 reverse 一并清掉，所以在这里建
    adb("reverse", "tcp:8096", "tcp:8096")
    if "tcp:8096" not in adb("reverse", "--list"):
        print("reverse 隧道建立失败")
        return 2
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)  # 清掉历史进度，避免上一轮的 key 干扰判定
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

    fab = find(res_id="fabPlaylist")
    if not fab:
        print("FAIL：找不到选集按钮（可能没识别到选集）")
        return 1
    tap(fab)
    time.sleep(2)
    row = find(text="01")
    if not row:
        print("FAIL：浮层里没有第 1 集")
        return 1
    tap(row)
    print("已点第 1 集，等待连播链 1->2->3 ...")

    if not wait_for(media_url(2), 60):
        print("FAIL：60 秒内第 2 集未开播（1->2 连播未触发）")
        return 1
    print("OK：1 -> 2 连播触发")
    if not wait_for(media_url(3), 60):
        print("FAIL：第 2 集播完后未连到第 3 集")
        return 1
    print("OK：2 -> 3 连播触发")
    with open(r"D:\tmp_dl\autonext.png", "wb") as fh:
        fh.write(adb("exec-out", "screencap", "-p", binary=True))
    print("判定: 连播链路全通（1->2->3）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
