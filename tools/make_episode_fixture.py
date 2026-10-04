"""复刻真实剧集站的选集结构，验证选集识别与"网页内点视频直接播"。

结构取自 akep5.xxpofweu.cc 真实 DOM（2026-10-03 抓包）：
  <a class=hg-web-play__ep href=ep-2/ data-ep-id=2>02</a>
视频用 MSE blob 流，模拟点它时 currentSrc 必然是 blob: 的情形
——这正是线上"浏览与播放器连不上"那个 bug 的触发条件。

用法：python tools/make_episode_fixture.py [--seconds 6] [--out testfixture_ep]

--seconds 6   从 testfixture/clip1.mp4 复制 6 秒短片（默认，供连播测试）
--seconds 60  用 ffmpeg 生成 60 秒长片（供暂停等需要稳定播放窗口的测试）
"""
import os
import subprocess
import sys

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
    args = sys.argv[1:]
    seconds = int(args[args.index("--seconds") + 1]) if "--seconds" in args else 6
    out = os.path.join(HERE, args[args.index("--out") + 1]) if "--out" in args else OUT
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(SRC, "clip1.mp4"), "rb") as f:
        data = f.read()
    clip_path = os.path.join(out, "clip1.mp4")
    if seconds == 6:
        with open(clip_path, "wb") as f:
            f.write(data)
    else:
        subprocess.run(
            ["ffmpeg", "-y", "-f", "lavfi", "-i", "testsrc=duration=%d:size=640x360:rate=24" % seconds,
             "-f", "lavfi", "-i", "sine=frequency=440:duration=%d" % seconds,
             "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip_path],
            check=True, capture_output=True,
        )
        print("已生成 %d 秒长片: %s" % (seconds, clip_path))
    with open(clip_path, "rb") as f:
        data = f.read()  # 各集页面统一用选定的片段，时长一致才有可预期的测试窗口
    page = HTML_HEAD + SCRIPT + HTML_TAIL
    with open(os.path.join(out, "index.html"), "w", encoding="utf-8") as f:
        f.write(page)
    # 每一集都给一份可播媒体，便于验证"选中某集 -> 加载该页 -> 发现媒体 -> 播放"
    for i in range(1, 5):
        sub = os.path.join(out, "ep-%d" % i)
        os.makedirs(sub, exist_ok=True)
        with open(os.path.join(sub, "clip1.mp4"), "wb") as f:
            f.write(data)
        with open(os.path.join(sub, "index.html"), "w", encoding="utf-8") as f:
            f.write(page)
    print("已生成:", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
