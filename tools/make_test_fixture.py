"""生成沙箱/发现链路的本地测试夹具：3 个真实可播放 mp4 + 一个含视频的测试页。

用法（先装出夹具，再用 python -m http.server 起服务）：
    python tools/make_test_fixture.py
夹具输出到 tools/testfixture/。
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(ROOT, "testfixture")
FFMPEG = "ffmpeg"

CLIPS = [
    ("clip1.mp4", 6, "testsrc=size=640x360:rate=24", 440),
    ("clip2.mp4", 4, "smptebars=size=640x360:rate=24", 660),
    ("clip3.mp4", 8, "color=c=green:size=640x360:rate=24", 880),
]

PAGE = """<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <title>SafeStream 测试页</title>
  <style>
    body { font-family: sans-serif; margin: 16px; }
    video { width: 100%; max-width: 640px; background: #000; margin: 8px 0; }
  </style>
</head>
<body>
  <h1>SafeStream 测试页</h1>
  <p>下面三个 video 元素用于验证自动发现。</p>

  <video id="v1" title="第一个测试视频" controls preload="none"
         src="clip1.mp4"></video>

  <video id="v2" controls preload="none">
    <source src="clip2.mp4" type="video/mp4">
  </video>

  <!-- 用 data-* 承载地址，验证扫描脚本能抓到非 src 的写法 -->
  <video id="v3" title="第三个测试视频" data-video-url="clip3.mp4" controls preload="none"></video>

  <h2>安全行为验证</h2>
  <p>下面几项应被 App 拦截，页面不应有任何系统级反应：</p>
  <ul>
    <li><a href="file:///sdcard/Download/">file:// 本地文件</a></li>
    <li><a href="intent://scan/#Intent;scheme=zxing;end">intent:// 拉起应用</a></li>
    <li><a href="market://details?id=com.example.app">market:// 跳应用商店</a></li>
    <li><img src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js" alt="广告脚本，应被拦截"></li>
    <li><img src="https://www.google-analytics.com/collect?v=1" alt="跟踪像素，应被拦截"></li>
  </ul>
</body>
</html>
"""


def make_video(name, duration, video_src, tone):
    dest = os.path.join(OUT, name)
    if os.path.exists(dest):
        print("skip", name)
        return
    cmd = [
        FFMPEG, "-y", "-loglevel", "error",
        "-f", "lavfi", "-i", f"{video_src}:duration={duration}",
        "-f", "lavfi", "-i", f"sine=frequency={tone}:duration={duration}",
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "ultrafast",
        "-c:a", "aac", "-shortest", dest,
    ]
    subprocess.run(cmd, check=True)
    print("made", name, os.path.getsize(dest), "bytes")


def main():
    os.makedirs(OUT, exist_ok=True)
    for clip in CLIPS:
        make_video(*clip)
    page = os.path.join(OUT, "index.html")
    with open(page, "w", encoding="utf-8") as fh:
        fh.write(PAGE)
    print("page", page)
    print("\nserve with:")
    print(f"  cd {OUT} && python -m http.server 8099")
    return 0


if __name__ == "__main__":
    sys.exit(main())
