"""确认剧集链接的真实形态（保留查询串）。

上一轮探针用 .split("?")[0] 剥掉了查询串，把 /video/5671/?ep=2 误判成 /video/5671/，
导致剧集数报 0。这次保留 ep 参数重新统计。

用法：python tools/probe_epurls.py <剧集页URL>
"""
import re
import sys

from playwright.sync_api import sync_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

SCAN = """() => {
  const out = [];
  document.querySelectorAll('a[href]').forEach(a => {
    if (a.href.indexOf('ep=') === -1) return;
    out.push({href: a.href, text: (a.textContent || '').trim().slice(0, 24)});
  });
  return {items: out};
}"""

EP_RE = re.compile(r"ep=(\d+)")


def main():
    url = sys.argv[1]
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        page = b.new_context(user_agent=UA, ignore_https_errors=True).new_page()
        page.goto(url, wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(12000)
        for _ in range(10):
            page.mouse.wheel(0, 3000)
            page.wait_for_timeout(800)
        page.wait_for_timeout(4000)
        items = page.evaluate(SCAN)["items"]
        eps = set()
        for href, _text in items:
            m = EP_RE.search(href)
            if m:
                eps.add(m.group(1))
        print("带 ep= 的链接总数:", len(items))
        print("去重集号:", len(eps))
        print("集号:", sorted(eps, key=int)[:60])
        print("")
        print("前 12 条原始 href:")
        for href, text in items[:12]:
            print("   ", href[-58:], "|", text)
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
