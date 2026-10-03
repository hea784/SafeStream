"""生成"安全契约测试页"：集中放置每一条防护要挡的攻击，用于回归验证。

为什么要这个：架构从双进程合并成单进程会动到 WebView 所在的 Activity。
合并后必须能逐条证明防护没有退化，而不是靠"应该还在"。

用法：python tools/make_security_fixture.py
"""
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "testfixture_sec")

HEAD = """<!DOCTYPE html>
<html lang="zh">
<head><meta charset="utf-8"><title>安全契约测试页</title></head>
<body style="font-family:sans-serif;margin:12px;background:#111;color:#eee">
<h1 id="title">安全契约测试页</h1>

<h2>1 危险 scheme 导航（应全部被拦）</h2>
<a id="atk-file" href="file:///sdcard/Download/">file</a>
<a id="atk-content" href="content://media/external/file/1">content</a>
<a id="atk-intent" href="intent://scan/#Intent;scheme=zxing;end">intent</a>
<a id="atk-market" href="market://details?id=com.example.app">market</a>
<a id="atk-js" href="javascript:void(0)">javascript</a>

<h2>2 广告与跟踪请求（应被网络层拦下）</h2>
<img id="atk-doubleclick" src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js">
<img id="atk-ga" src="https://www.google-analytics.com/g/collect?v=2&tid=G-TEST">
<img id="atk-beacon" src="https://static.cloudflareinsights.com/beacon.min.js">
<img id="atk-adsdk" src="https://api-ad1.adsdk1.cc/api/sdk/ad/request">
<img id="atk-ip" src="https://54.169.219.76:15212/api/eventTracking/batchReport.json">

<h2>3 能力请求（应被拒绝）</h2>
<button id="atk-geo" onclick="askGeo()">定位</button>
<button id="atk-cam" onclick="askCam()">摄像头</button>
<button id="atk-popup" onclick="openPopup()">弹窗</button>
<button id="atk-file" onclick="pickFile()">选文件</button>

<h2>4 下载（应被阻止）</h2>
<a id="atk-dl" href="clip1.mp4" download="payload.mp4">下载</a>

<h2 id="verdict">VERDICT:PENDING</h2>
"""
JS = [
    "<script>",
    "var marks = [];",
    "function mark(m) { marks.push(m);",
    "  var el = document.getElementById('verdict');",
    "  if (el) { el.textContent = 'VERDICT:' + marks.join(','); } }",
    "function askGeo() {",
    "  if (!navigator.geolocation) { mark('geo-unsupported'); return; }",
    "  navigator.geolocation.getCurrentPosition(",
    "    function () { mark('geo-GRANTED'); },",
    "    function () { mark('geo-denied'); } ); }",
    "function askCam() {",
    "  if (!navigator.mediaDevices) { mark('cam-unsupported'); return; }",
    "  navigator.mediaDevices.getUserMedia({video: true}).then(",
    "    function () { mark('cam-GRANTED'); },",
    "    function () { mark('cam-denied'); } ); }",
    "function openPopup() {",
    "  var w = window.open('about:blank', '_blank');",
    "  mark(w ? 'popup-OPENED' : 'popup-blocked'); }",
    "function pickFile() {",
    "  var i = document.createElement('input'); i.type = 'file';",
    "  i.addEventListener('change', function () { mark('file-picked'); });",
    "  i.addEventListener('cancel', function () { mark('file-cancelled'); });",
    "  i.click();",
    "  setTimeout(function () { mark('file-no-response'); }, 1200); }",
    "</script>",
    "</body>",
    "</html>",
]


def main():
    os.makedirs(OUT, exist_ok=True)
    src = os.path.join(HERE, "testfixture", "clip1.mp4")
    if os.path.exists(src):
        with open(src, "rb") as f:
            data = f.read()
        with open(os.path.join(OUT, "clip1.mp4"), "wb") as f:
            f.write(data)
    with open(os.path.join(OUT, "index.html"), "w", encoding="utf-8") as f:
        f.write(HEAD + "\n".join(JS))
    print("已生成:", OUT)
    print("起服务: cd", OUT, "&& python -m http.server 8095 --bind 127.0.0.1")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
