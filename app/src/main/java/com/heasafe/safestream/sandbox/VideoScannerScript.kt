package com.heasafe.safestream.sandbox

/**
 * 注入页面的扫描脚本。
 *
 * 它只做一件事：读取 DOM 和 Performance 资源表，把视频地址发回来。
 * 不修改页面、不读取输入框、不访问 localStorage 之外的数据。
 *
 * 通过 WebViewCompat.addWebMessageListener 回传 —— 这是受白名单 origin 约束的通道，
 * 比 addJavascriptInterface 安全得多（后者会把整个原生对象暴露给任意脚本）。
 */
object VideoScannerScript {

    const val MESSAGE_NAME = "SafeStreamBridge"

    val SOURCE: String = """
    (function () {
      if (window.__safestream_installed) return;
      window.__safestream_installed = true;

      var EXT = /\.(m3u8|mp4|webm|mov|m4v|mkv|mpd)(\?|#|$)/i;

      function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return null; } }
      function send(list) {
        try { window.SafeStreamBridge.postMessage(JSON.stringify(list)); } catch (e) {}
      }

      function pickTitle(el, idx) {
        var t = '';
        try {
          t = (el.getAttribute('title') || '').trim();
          if (!t) { var a = el.closest('a'); if (a) t = (a.getAttribute('title') || a.textContent || '').trim(); }
          if (!t) t = (el.textContent || '').trim();
        } catch (e) {}
        if (!t || t.length > 120) t = '视频 ' + (idx + 1);
        return t;
      }

      function scanDom() {
        var out = [], seen = {};
        var nodes = document.querySelectorAll('video');
        for (var i = 0; i < nodes.length; i++) {
          var v = nodes[i], cands = [];
          if (v.currentSrc) cands.push({ u: v.currentSrc, m: '' });
          if (v.src) cands.push({ u: v.src, m: '' });
          var srcs = v.querySelectorAll('source');
          for (var j = 0; j < srcs.length; j++) {
            var s = srcs[j];
            cands.push({ u: s.getAttribute('src') || s.getAttribute('data-src') || '', m: s.getAttribute('type') || '' });
          }
          var ld = v.getAttribute('data-video-url') || v.getAttribute('data-hls') || '';
          if (ld) cands.push({ u: ld, m: '' });

          for (var k = 0; k < cands.length; k++) {
            var u = abs(cands[k].u);
            if (!u || seen[u]) continue;
            if (!EXT.test(u)) continue;
            seen[u] = 1;
            out.push({
              url: u,
              title: pickTitle(v, out.length),
              mimeType: cands[k].m || '',
              durationMs: (v.duration && isFinite(v.duration)) ? Math.round(v.duration * 1000) : 0,
              sourcePage: location.href
            });
          }
        }
        return out;
      }

      // 抓 Performance 资源表：很多站点用 blob/MSE，只在网络层能拿到真实 .m3u8
      function scanNetwork() {
        var out = [], seen = {};
        var entries = [];
        try { entries = performance.getEntriesByType('resource'); } catch (e) { return out; }
        for (var i = 0; i < entries.length; i++) {
          var u = entries[i].name;
          if (!EXT.test(u)) continue;
          if (seen[u]) continue;
          seen[u] = 1;
          out.push({
            url: u,
            title: '流 ' + (out.length + 1),
            mimeType: '',
            durationMs: 0,
            sourcePage: location.href
          });
        }
        return out;
      }

      function merge() {
        var map = {}, order = [];
        var all = scanDom().concat(scanNetwork());
        for (var i = 0; i < all.length; i++) {
          var it = all[i];
          if (map[it.url]) continue;
          map[it.url] = it; order.push(it);
        }
        send(order);
      }

      // 站点常有懒加载，需要观察 DOM 变化后重扫
      try {
        var timer = null;
        var obs = new MutationObserver(function () {
          if (timer) clearTimeout(timer);
          timer = setTimeout(merge, 800);
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) {}

      if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(merge, 600);
        setTimeout(merge, 2500);
      } else {
        document.addEventListener('DOMContentLoaded', function () { setTimeout(merge, 600); });
        window.addEventListener('load', function () { setTimeout(merge, 1200); });
      }
    })();
    """.trimIndent()
}
