"""uiautomator dump 解析辅助：按文本/资源 id 找控件并算出可点击中心坐标。

直接用正则从 dump 里抠 bounds 很容易跨节点误匹配（曾把两个数字当字符串拼成
356963 这种越界坐标），所以这里老老实实解析 XML。

用法：
    python tools/uihelper.py center --text "仍要加载"
    python tools/uihelper.py center --id loadButton
    python tools/uihelper.py texts
    python tools/uihelper.py tap --text "加载" [--serial emulator-5554]
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = r"D:\dev-tools\sdks\android-sdk\platform-tools\adb.exe"
SERIAL = "emulator-5554"
REMOTE = "/sdcard/ui_dump.xml"


def adb(*args, serial=SERIAL, binary=False):
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    p = subprocess.run(cmd, capture_output=True)
    if p.returncode != 0:
        raise SystemExit("adb failed: " + p.stderr.decode("utf-8", "replace"))
    return p.stdout if binary else p.stdout.decode("utf-8", "replace")


def dump(serial=SERIAL):
    """dump 后把 XML 拉回本地解析，避免设备端编码问题。"""
    adb("shell", "uiautomator", "dump", REMOTE, serial=serial)
    return adb("shell", "cat", REMOTE, serial=serial, binary=True)


def parse(serial=SERIAL):
    raw = dump(serial)
    return ET.fromstring(raw.decode("utf-8", "replace"))


def center(bounds):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
    if not m:
        return None
    x1, y1, x2, y2 = (int(g) for g in m.groups())
    return ((x1 + x2) // 2, (y1 + y2) // 2)


def find(root, text=None, res_id=None):
    suffix = f":id/{res_id}" if res_id else None
    for node in root.iter("node"):
        if text is not None and node.get("text") != text:
            continue
        if suffix is not None and not (node.get("resource-id") or "").endswith(suffix):
            continue
        c = center(node.get("bounds", ""))
        if c:
            return c
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["center", "texts", "tap", "dump"])
    ap.add_argument("--text")
    ap.add_argument("--id", dest="res_id")
    ap.add_argument("--serial", default=SERIAL)
    args = ap.parse_args()

    root = parse(args.serial)

    if args.cmd == "texts":
        for node in root.iter("node"):
            t = (node.get("text") or "").strip()
            rid = node.get("resource-id") or ""
            if t:
                c = center(node.get("bounds", ""))
                print(f"{c}\t{rid.split('/')[-1]}\t{t}")
        return 0

    if args.cmd == "dump":
        print(dump(args.serial).decode("utf-8", "replace"))
        return 0

    point = find(root, text=args.text, res_id=args.res_id)
    if not point:
        print("NOT_FOUND", file=sys.stderr)
        return 1
    if args.cmd == "tap":
        adb("shell", "input", "tap", str(point[0]), str(point[1]), serial=args.serial)
        print(f"tapped {point}")
    else:
        print(f"{point[0]} {point[1]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
