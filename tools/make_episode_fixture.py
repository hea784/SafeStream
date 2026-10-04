"""复刻真实剧集站的选集结构，验证选集识别与"网页内点视频直接播"。

结构取自 akep5.xxpofweu.cc 真实 DOM（2026-10-03 抓包）：
  <a class=hg-web-play__ep href=ep-2/ data-ep-id=2>02</a>
视频用 MSE blob 流，模拟点它时 currentSrc 必然是 blob: 的情形
——这正是线上"浏览与播放器连不上"那个 bug 的触发条件。

用法：python tools/make_episode_fixture.py
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "testfixture")
OUT = os.path.join(HERE, "testfixture_ep")

HTML_HEAD = """<!DOCTYPE html>
<html lang="zh">
<head><meta charset="utf-8"><title>剧集夹具</title></head>
<body style="font-family:sans-serif;margin:16px;background:#111;color:#eee">
  <h1>剧集夹具</h1>
  <video id="v" style="width:100%;max-width:480px;background:#000" controls></video>
  <div class="hg-web-play__ep-grid">
    <a class="hg-web-play__ep is-active" href="/ep-1/" data-ep-id="1">01</a>
    <a class="hg-web-play__ep" href="/ep-2/" data-ep-id="2">02</a>
    <a class="hg-web-play__ep" href="/ep-3/" data-ep-id="3">03</a>
    <a class="hg-web-play__ep" href="/ep-4/" data-ep-id="4">04</a>
  </div>
  <div class="xg-fs-eps__row">
    <button class="xg-fs-eps__item is-active" data-fs-ep="1">01</button>
    <button class="xg-fs-eps__item" data-fs-ep="2">02</button>
    <button class="xg-fs-eps__item" data-fs-ep="3">03</button>
  </div>
"""

HTML_TAIL = """</body>
</html>
"""

# 用 MSE 造真正的 blob 流：点它时 currentSrc 就是 blob:...，与线上情形一致。
SCRIPT = """
  <script>
  (function () {
    var v = document.getElementById('v');
    try {
      var ms = new MediaSource();
      v.src = URL.createObjectURL(ms);
      ms.addEventListener('sourceopen', function () {
        // 必须是带 codecs 的合法 MIME，否则 WebView 抛 NotSupportedError，
        // 媒体请求不会发出，整条"发现 -> 播放"链路都测不到
        var sb = ms.addSourceBuffer('video/mp4; codecs=avc1.42E01E');
        fetch('clip1.mp4').then(function (r) { return r.arrayBuffer(); })
          .then(function (b) { try { sb.appendBuffer(b); } catch (e) {} });
      });
    } catch (e) {}
  })();
  </script>
"""
def main():
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(SRC, "clip1.mp4"), "rb") as f:
        data = f.read()
    with open(os.path.join(OUT, "clip1.mp4"), "wb") as f:
        f.write(data)
    page = HTML_HEAD + SCRIPT + HTML_TAIL
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)
    # 每一集都给一份可播媒体，便于验证"选中某集 -> 加载该页 -> 发现媒体 -> 播放"
    for i in range(1, 5):
        sub = os.path.join(OUT, "ep-%d" % i)
        os.makedirs(sub, exist_ok=True)
        with open(os.path.join(sub, "clip1.mp4"), "wb") as f:
            f.write(data)
        with open(os.path.join(sub, "index.html"), "w", encoding="utf-8") as f:
            f.write(page)
    print("已生成:", OUT)
    print("起服务: cd", OUT, "&& python -m http.server 8096 --bind 127.0.0.1")
    print("再执行: adb -s emulator-5554 reverse tcp:8096 tcp:8096")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
