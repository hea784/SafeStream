"""直接看选集项的真实 DOM 结构：标签文字是 01/02/03 的元素到底是什么。

前面两次推断都落空：既不是 /video/<id>/<n> 路径，也不是 ?ep=n 查询串。
不再猜，直接把元素连同祖先的 outerHTML 打出来。

用法：python tools/probe_eps_dom.py <剧集页URL>
"""
import sys

from playwright.sync_api import sync_playwright

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

SCAN = """() => {
  const found = [];
  const all = document.querySelectorAll('*');
  for (const el of all) {
    const kids = Array.from(el.children);
    if (kids.length < 3) continue;
    const texts = kids.map(k => (k.textContent || '').trim());
    // 找像选集的一组：子元素文字形如 01 02 03 / 1 2 3
    const nums = texts.filter(t => /^\\d{1,3}$/.test(t));
    if (nums.length >= 3) {
      found.push({
        tag: el.tagName,
        cls: el.className.toString().slice(0, 120),
        count: kids.length,
        numbers: nums.slice(0, 30),
        sampleHtml: kids.slice(0, 4).map(k => k.outerHTML.slice(0, 260))
      });
      if (found.length >= 3) break;
    }
  }
  return found;
}"""


def main():
    url = sys.argv[1]
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome", headless=True, args=["--no-sandbox"])
        page = b.new_context(user_agent=UA, ignore_https_errors=True).new_page()
        page.goto(url, wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(12000)
        for _ in range(8):
            page.mouse.wheel(0, 3000)
            page.wait_for_timeout(800)
        page.wait_for_timeout(3000)
        found = page.evaluate(SCAN)
        print("候选选集容器:", len(found))
        for f in found:
            print("")
            print("  <%s class=%s> 子元素 %d 个" % (f["tag"], f["cls"], f["count"]))
            print("  数字标签:", f["numbers"])
            for h in f["sampleHtml"]:
                print("  ", h)
        b.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
