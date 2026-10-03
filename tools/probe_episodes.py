"""探测剧集站的"选集"结构：剧集列表在哪、链接长什么样、要不要翻页。

为什么必须先探：SafeStream 目前只能从当前页面发现 1 个视频。连续剧需要的是
整份选集列表，而列表可能藏在 DOM 里、可能写在页面内联 JSON 里、也可能要调接口。
不先看清楚结构就动手，只会写出跟真实站点对不上的代码。

用法：python tools/probe_episodes.py <剧集页URL>
"""
import json
import re
import sys

from playwright.sync_api import sync_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

SELECTORS = [
    ".episode-list", ".eps", ".ep-list", ".playlist", ".chapter-list",
    ".listbox", ".episode", ".episodes", "[class*=epis]", "[class*=episode]",
    "[class*=chapter]", "[class*=playlist]", "[class*=drama]",
]


def classify(links, base):
    groups = {}
    for href, text in links:
        if base not in href:
            continue
        path = href.split(base, 1)[1].split("?")[0].split("#")[0]
        key = re.sub(r"/\d+", "/N", path)
        groups.setdefault(key, []).append((path, text))
    return groups


def build_probe(sel_list):
    return """() => {
  const links = Array.from(document.querySelectorAll('a[href]')).map(a => ({
    href: a.href, text: (a.textContent || '').trim().slice(0, 40)
  }));
  const sels = %s;
  const containers = [];
  sels.forEach(s => {
    document.querySelectorAll(s).forEach(el => {
      containers.push({
        sel: s, count: el.querySelectorAll('a').length,
        cls: el.className.toString().slice(0, 80)
      });
    });
  });
  return { links: links, containers: containers, total: links.length };
}""" % repr(sel_list).replace("'", '"')
def main():
    url = sys.argv[1]
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        ctx = browser.new_context(user_agent=UA, ignore_https_errors=True)
        page = ctx.new_page()
        api_calls = []

        def on_request(r):
            if re.search(r"(api|list|episode|catalog|chapter)", r.url, re.I):
                api_calls.append(r.url)

        page.on("request", on_request)
        print("打开:", url)
        try:
            page.goto(url, wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(12000)
        except Exception as exc:
            print("加载异常:", str(exc)[:150])
        data = page.evaluate(build_probe(SELECTORS))
        base = url.split("//")[1].split("/")[0]
        print("")
        print("站内链接总数:", data["total"])
        print("")
        print("[选集容器]")
        for c in data["containers"][:15]:
            print("   %-26s links=%-4d %s" % (c["sel"], c["count"], c["cls"][:40]))
        groups = classify([(l["href"], l["text"]) for l in data["links"]], base)
        print("")
        print("[按路径归类前 15 组]")
        for key, items in sorted(groups.items(), key=lambda kv: -len(kv[1]))[:15]:
            print("   %-38s %3d 条  例: %s | %s"
                  % (key, len(items), items[0][0], items[0][1]))
        print("")
        print("[疑似接口请求]")
        for u in api_calls[:10]:
            print("   ", u[:150])
        out = {"url": url,
               "groups": {k: v[:60] for k, v in groups.items()},
               "containers": data["containers"][:20],
               "api": api_calls[:20]}
        with open(r"D:\tmp_episodes.json", "w", encoding="utf-8") as fh:
            json.dump(out, fh, ensure_ascii=False, indent=1)
        print("")
        print("详情写入 D:\\tmp_episodes.json")
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
