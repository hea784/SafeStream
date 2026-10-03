"""在真实浏览器里直接跑扫描脚本，验证 JS 逻辑本身对不对。

为什么需要：设备上"全静默"有两种可能——脚本没产出，或 Android 桥接不存在。
在浏览器里把 SafeStreamBridge 换成收集器，就能把两者分开。

用法：python tools/test_scanner_js.py
"""
import os
import re
import sys

from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCANNER_KT = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "heasafe", "safestream",
    "sandbox", "VideoScannerScript.kt")

STUB = """
  window.__collected = [];
  window.SafeStreamBridge = {
    postMessage: function (s) { window.__collected.push(s); }
  };
"""

COLLECT = "() => window.__collected || []"


def extract_js():
    with open(SCANNER_KT, encoding="utf-8") as fh:
        text = fh.read()
    m = re.search(r'val SOURCE: String = """(.*?)"""', text, re.S)
    if not m:
        print("在 VideoScannerScript.kt 里没找到 SOURCE")
        return None
    return m.group(1)
def main():
    js = extract_js()
    if not js:
        return 2
    print("脚本长度:", len(js))
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        page = b.new_context().new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto("http://127.0.0.1:8096/index.html", wait_until="domcontentloaded")
        page.evaluate(STUB)
        try:
            page.evaluate(js)
            print("脚本注入成功")
        except Exception as exc:
            print("注入抛异常:", str(exc)[:300])
        page.wait_for_timeout(9000)
        got = page.evaluate(COLLECT)
        print("收到消息数:", len(got))
        n_ep = 0
        n_batch = 0
        n_single = 0
        for g in got:
            if "episodes" in g:
                n_ep += 1
            elif "batch" in g:
                n_batch += 1
            elif "kind" in g:
                n_single += 1
        print("消息类型: 选集=%d 批量=%d 单条=%d" % (n_ep, n_batch, n_single))
        eps = [g for g in got if "episodes" in g]
        if eps:
            print("")
            print("选集消息样例:", eps[0][:400])
        if errors:
            print("")
            print("页面异常:")
            for e in errors[:5]:
                print("   ", e[:200])
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
