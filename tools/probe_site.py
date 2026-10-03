"""探测目标站点的视频分发机制与广告加载行为。

为什么要探：页面上看不到 <video> / m3u8 时，视频多半是 JS 运行时用
MediaSource(MSE) 喂出来的 blob: 流。这类流的真实地址只存在于网络层，
决定 SafeStream 要在哪一层做发现（DOM 扫描 / 拦截网络请求 / 注入 fetch-XHR 钩子）。

同时统计广告 SDK 与广告请求，用于校准拦截层。

用法：
    python tools/probe_site.py https://akep5.xxpofweu.cc/ https://huangguoai.com/
"""
import re
import sys

from playwright.sync_api import sync_playwright

MEDIA_RE = re.compile(r"\.(m3u8|mp4|webm|mov|m4v|ts|m4s|cmfv|cmfa)(\?|$)", re.I)
AD_RE = re.compile(
    r"(googlesyndication|doubleclick|googletagmanager|google-analytics|"
    r"ssp|ads\.|/ads/|adserver|pagead|popads|propeller|adcash|"
    r"umeng|analytics|/track|collect\?|/beacon)", re.I)
PLAYER_RE = re.compile(
    r"(jwplayer|videojs|dplayer|artplayer|shaka|hls|"
    r"mpegts|dash|ckplayer|polyplayer|player\.)", re.I)

DOM_PROBE = """() => {
  const vs = Array.from(document.querySelectorAll('video'));
  const res = performance.getEntriesByType('resource');
  return {
    title: document.title,
    videoCount: vs.length,
    srcs: vs.map(v => (v.currentSrc || v.src || '(none)').slice(0, 200)),
    hasMSE: typeof MediaSource !== 'undefined',
    blobCount: res.filter(r => r.name.startsWith('blob:')).length,
    totalResources: res.length,
    links: Array.from(document.querySelectorAll('a[href]')).map(a => a.href)
      .filter(h => /play|detail|episode|video|drama/i.test(h)).slice(0, 25),
  };
}"""
def probe(url, browser):
    ctx = browser.new_context(
        user_agent=("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"),
        ignore_https_errors=True,
        viewport={"width": 1080, "height": 2400},
    )
    page = ctx.new_page()
    media, ads, players = [], [], []

    def on_request(req):
        u = req.url
        if MEDIA_RE.search(u):
            media.append(u)
        elif AD_RE.search(u):
            ads.append(u)
        if PLAYER_RE.search(u) and ".js" in u:
            players.append(u)

    page.on("request", on_request)
    print("=" * 66)
    print("URL:", url)
    try:
        page.goto(url, wait_until="domcontentloaded", timeout=45000)
        page.wait_for_timeout(18000)
    except Exception as exc:
        print("  加载异常:", str(exc)[:160])
    try:
        dom = page.evaluate(DOM_PROBE)
    except Exception as exc:
        dom = {"error": str(exc)[:160]}
    print("  title:", dom.get("title"))
    print("  <video> 数量:", dom.get("videoCount"))
    for s in dom.get("srcs", []):
        print("    src:", s)
    print("  MSE 可用:", dom.get("hasMSE"), "| blob 资源:", dom.get("blobCount"),
          "| 总请求:", dom.get("totalResources"))
    print("  媒体请求:", len(media))
    for m in media[:15]:
        print("    MEDIA:", m[:150])
    print("  广告/跟踪请求:", len(ads))
    for a in ads[:15]:
        print("    AD:", a[:150])
    print("  播放器脚本:", len(players))
    for p in players[:8]:
        print("    PLAYER:", p[:150])
    # 首页通常只是落地页，真实视频在详情/播放页。挑一个候选深挖。
    candidates = [h for h in dom.get("links", []) if h.startswith(url.split("//")[0])]
    if candidates:
        deep = candidates[0]
        print("-" * 66)
        print("深入探测:", deep)
        media.clear()
        ads.clear()
        try:
            page.goto(deep, wait_until="domcontentloaded", timeout=45000)
            page.wait_for_timeout(20000)
        except Exception as exc:
            print("  加载异常:", str(exc)[:160])
        try:
            dom2 = page.evaluate(DOM_PROBE)
        except Exception as exc:
            dom2 = {"error": str(exc)[:160]}
        print("  title:", dom2.get("title"))
        print("  <video> 数量:", dom2.get("videoCount"))
        for s in dom2.get("srcs", []):
            print("    src:", s)
        print("  blob 资源:", dom2.get("blobCount"), "| 总请求:", dom2.get("totalResources"))
        print("  媒体请求:", len(media))
        for m in media[:20]:
            print("    MEDIA:", m[:170])
        print("  广告/跟踪请求:", len(ads))
        for a in ads[:20]:
            print("    AD:", a[:170])
    ctx.close()


def main():
    urls = sys.argv[1:] or ["https://akep5.xxpofweu.cc/", "https://huangguoai.com/"]
    with sync_playwright() as pw:
        # 优先用系统已安装的 Chrome，避免为了探一个站点去下载几百 MB 浏览器
        browser = None
        for kwargs in (
            {"channel": "chrome", "headless": True, "args": ["--no-sandbox"]},
            {"headless": True, "args": ["--no-sandbox"]},
        ):
            try:
                browser = pw.chromium.launch(**kwargs)
                print("browser:", kwargs.get("channel", "bundled"))
                break
            except Exception as exc:
                print("launch 失败:", str(exc)[:120])
        if browser is None:
            print("没有可用浏览器；装 Chrome 或跑 playwright install chromium")
            return 2
        for url in urls:
            try:
                probe(url, browser)
            except Exception as exc:
                print("=" * 66)
                print("URL:", url, "探测失败:", str(exc)[:200])
        browser.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
