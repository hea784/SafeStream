"""专探"选集"：把懒加载的剧集列表逼出来，并找出背后的接口。

已知结构（2026-10-03 实测）：剧集页 /video/<id>/ 的选集是 /video/<id>/01 这种，
但首屏 DOM 里只有第 01 集，其余要触发懒加载才有。

要回答两个问题：
  1. 选集在哪个容器里？滚动它能不能把全部剧集加载出来？
  2. 有没有更省事的接口能直接拿到完整选集？

用法：python tools/probe_eplist.py <剧集页URL>
"""
import re
import sys

from playwright.sync_api import sync_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

SCAN = """() => {
  const re = /\\/video\\/(\\d+)\\/(\\d+)/;
  const ep = [];
  document.querySelectorAll('a[href]').forEach(a => {
    const m = re.exec(a.href);
    if (m) ep.push({ep: m[2], text: (a.textContent || '').trim().slice(0, 20)});
  });
  let best = null;
  document.querySelectorAll('div,ul,section,nav').forEach(el => {
    const links = Array.from(el.querySelectorAll('a[href]'));
    const n = links.filter(a => re.test(a.href)).length;
    if (n >= 2 && (!best || n < best.n)) {
      best = {n: n, cls: el.className.toString().slice(0, 90), id: el.id || '', tag: el.tagName};
    }
  });
  const txt = document.body.innerText || '';
  const m = txt.match(/(\\d+)\\s*集/) || txt.match(/(共|全集)[^\\n]{0,6}(\\d+)/);
  return {episodes: ep, container: best, pageSays: m ? m[0] : null};
}"""
def main():
    url = sys.argv[1]
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        page = browser.new_context(user_agent=UA, ignore_https_errors=True).new_page()
        api = []
        page.on("request", lambda r: api.append(r.url) if re.search(r"/api/", r.url, re.I) else None)
        page.goto(url, wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(10000)
        first = page.evaluate(SCAN)
        print("滚动前剧集链接:", len(first["episodes"]))
        print("页面自称集数:", first["pageSays"])
        print("最集中的选集容器:", first["container"])
        for _ in range(12):
            page.mouse.wheel(0, 3000)
            page.wait_for_timeout(900)
        page.wait_for_timeout(4000)
        after = page.evaluate(SCAN)
        eps = {e["ep"] for e in after["episodes"]}
        print("")
        print("滚动后剧集链接:", len(after["episodes"]), " 去重集号:", len(eps))
        print("集号样本:", sorted(eps)[:40])
        print("")
        print("[命中 /api/ 的请求]")
        for u in sorted(set(api))[:15]:
            print("   ", u[:160])
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
