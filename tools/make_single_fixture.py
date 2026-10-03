"""生成只有一个视频的测试页，复现真实目标站的条件。

为什么单独造：MainActivity 只在"整个列表恰好 1 条"时自动播放。
真实站点（黄果短剧播放页）扫描出来就是 1 条，于是每次扫描上报都会触发一次
自动播放 —— 这正是画面闪烁的触发条件。三视频的旧夹具覆盖不到这条路径。

用法：python tools/make_single_fixture.py
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "testfixture")
OUT = os.path.join(HERE, "testfixture_single")

PAGE = """<!DOCTYPE html>
<html lang="zh">
<head>
  <meta charset="utf-8">
  <title>单视频测试页</title>
  <style>
    body { font-family: sans-serif; margin: 16px; }
    video { width: 100%; max-width: 640px; background: #000; }
  </style>
</head>
<body>
  <h1>单视频测试页</h1>
  <video id="v1" title="唯一的测试视频" controls preload="none" src="clip1.mp4"></video>
  <div id="churn"></div>
  <script>
    // 模拟广告位/懒加载造成的持续 DOM 变动，用来逼出扫描脚本的重复上报
    let n = 0;
    setInterval(function () {
      const d = document.getElementById('churn');
      d.insertAdjacentHTML('beforeend', '<span>' + (n++) + '</span>');
      if (n > 200) d.innerHTML = '';
    }, 700);
  </script>
</body>
</html>
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    src_clip = os.path.join(SRC, "clip1.mp4")
    if not os.path.exists(src_clip):
        print("先跑 make_test_fixture.py 生成 clip1.mp4")
        return 2
    with open(src_clip, "rb") as src:
        data = src.read()
    with open(os.path.join(OUT, "clip1.mp4"), "wb") as dst:
        dst.write(data)
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as fh:
        fh.write(PAGE)
    print("已生成:", OUT)
    print("起服务： cd", OUT, "&& python -m http.server 8097 --bind 127.0.0.1")
    print("再执行： adb -s emulator-5554 reverse tcp:8097 tcp:8097")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
