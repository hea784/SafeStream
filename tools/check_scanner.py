"""扫描脚本语法与完整性校验。

scanner.js 移到 assets 后，改一个字符不必重编 APK——但也不能没有检查。
node --check 抓语法错误，标记断言抓"改丢了关键钩子"。

用法：python tools/check_scanner.py   （需要 PATH 里有 node）
"""
import subprocess
import sys
import os

HERE = os.path.dirname(os.path.abspath(__file__))
JS = os.path.join(HERE, "..", "app", "src", "main", "assets", "scanner.js")

REQUIRED_MARKERS = [
    "SafeStreamBridge",        # 回传通道
    "__safestream_installed",  # 幂等标记
    "scanEpisodes",            # 选集识别
    "MediaSource",             # MSE 钩子
    "requestMediaKeySystemAccess",  # DRM 检测
]


def main():
    if not os.path.exists(JS):
        print("FAIL: 找到不到 " + os.path.normpath(JS))
        return 1
    r = subprocess.run(["node", "--check", JS], capture_output=True, text=True)
    if r.returncode != 0:
        print("FAIL: 语法错误\n" + (r.stderr or r.stdout)[:800])
        return 1
    print("OK: node --check 通过")
    text = open(JS, encoding="utf-8").read()
    missing = [m for m in REQUIRED_MARKERS if m not in text]
    if missing:
        print("FAIL: 缺少关键标记 " + ", ".join(missing))
        return 1
    print("OK: %d 个关键标记齐全（%d 字节）" % (len(REQUIRED_MARKERS), len(text)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
