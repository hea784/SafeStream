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

      function post(url, kind) {
        try {
          window.SafeStreamBridge.postMessage(JSON.stringify({
            url: url, kind: kind, page: location.href
          }));
        } catch (e) {}
      }

      function abs(u) { try { return new URL(u, document.baseURI).href; } catch (e) { return null; } }
      function send(list) {
        try {
          window.SafeStreamBridge.postMessage(JSON.stringify({ batch: list }));
        } catch (e) {}
      }

      // ---- 网络层钩子：MSE/blob 页面的真实地址只存在于这里 ----
      // 很多播放器（hls.js 等）用 fetch 或 XHR 拉清单，DOM 上只有 blob:。
      // 钩住这两处能在请求发出的瞬间拿到 m3u8，不必等用户点播放。
      function hookNet() {
        if (window.__safestream_net) return;
        window.__safestream_net = true;

        try {
          var of = window.fetch;
          if (of) {
            window.fetch = function (input) {
              try {
                var u = (typeof input === 'string') ? input : (input && input.url);
                if (u && EXT.test(u)) post(u, 'fetch');
              } catch (e) {}
              return of.apply(this, arguments);
            };
          }
        } catch (e) {}

        try {
          var oo = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) {
            try { if (url && EXT.test(url)) post(url, 'xhr'); } catch (e) {}
            return oo.apply(this, arguments);
          };
        } catch (e) {}

        // MediaSource 的 mime 也能佐证流类型，一并上报
        try {
          if (window.MediaSource && MediaSource.prototype.addSourceBuffer) {
            var ob = MediaSource.prototype.addSourceBuffer;
            MediaSource.prototype.addSourceBuffer = function (t) {
              try { post('mse:' + t, 'mse'); } catch (e) {}
              return ob.apply(this, arguments);
            };
          }
        } catch (e) {}
      }

      // ---- 点击捕获：用户在网页里点某个视频时，把它的地址报回去 ----
      // 这样"挑哪个看哪个"可以直接在网页里完成，不用退回列表再点一次。
      function hookClicks() {
        if (window.__safestream_click) return;
        window.__safestream_click = true;
        document.addEventListener('click', function (ev) {
          try {
            var v = ev.target && ev.target.closest ? ev.target.closest('video') : null;
            if (!v) return;
            var u = v.currentSrc || v.src || v.getAttribute('data-video-url') || '';
            if (u && u.indexOf('blob:') !== 0) {
              // 普通地址：直接上报这个视频
              window.SafeStreamBridge.postMessage(JSON.stringify({
                url: u, kind: 'click', page: location.href
              }));
              return;
            }
            // blob: 流（MSE）：地址在页面外没有意义，但用户已经用点击表达了
            // "要播这个"，此时网络钩子早就抓到真实清单了，让主进程去播已发现的媒体。
            window.SafeStreamBridge.postMessage(JSON.stringify({
              url: '', kind: 'play-found', page: location.href
            }));
          } catch (e) {}
        }, true);
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
        var eps = scanEpisodes();
        if (eps.length) {
          try {
            window.SafeStreamBridge.postMessage(JSON.stringify({ episodes: eps }));
          } catch (e) {}
        }
      }

      // ---- 选集识别 ----
      // 连续剧的每一集是独立页面（实测 /video/<剧id>/ep-<集号>/），不是媒体地址，
      // 所以必须单独识别出来，交给上层去"加载该页 -> 发现其媒体 -> 播放"。
      function scanEpisodes() {
        var out = [], seen = {};
        function add(url, ep, title) {
          if (!url) return;
          var abs;
          try { abs = new URL(url, location.href).href; } catch (e) { return; }
          if (seen[abs]) return;
          seen[abs] = 1;
          out.push({url: abs, ep: ep, title: title || '', page: location.href});
        }
        var nodes = document.querySelectorAll('[data-ep-id],[data-fs-ep],[data-episode],a[href*="/ep-"]');
        for (var i = 0; i < nodes.length; i++) {
          var el = nodes[i];
          var ep = el.getAttribute('data-ep-id') || el.getAttribute('data-fs-ep')
                || el.getAttribute('data-episode');
          var href = el.getAttribute('href');
          if (!ep && href) {
            var m = href.match(/\/ep-(\d+)/);
            if (m) ep = m[1];
          }
          if (!ep) continue;
          // 没有 href 的按钮走 JS，构造不出地址就跳过，不硬编
          if (!href) continue;
          add(href, ep, (el.textContent || '').trim().slice(0, 40));
        }
        out.sort(function (a, b) { return parseInt(a.ep, 10) - parseInt(b.ep, 10); });
        return out;
      }

      // 站点常有懒加载，需要观察 DOM 变化后重扫
      try {
        var timer = null;
        // 防抖要够长：广告位和懒加载会让 DOM 持续变动，间隔太短会疯狂重复上报，
        // 上层就得不停重建播放列表（表现为画面闪烁、列表跳动）。
        var lastSent = 0;
        var obs = new MutationObserver(function () {
          if (timer) clearTimeout(timer);
          timer = setTimeout(function () {
            var now = Date.now();
            if (now - lastSent < 5000) return;   // 最短 5 秒上报一次
            lastSent = now;
            merge();
          }, 1500);
        });
        obs.observe(document.documentElement, { childList: true, subtree: true });
      } catch (e) {}

      if (document.readyState === 'complete' || document.readyState === 'interactive') {
        hookNet();
        hookClicks();
        setTimeout(merge, 600);
        setTimeout(merge, 2500);
      } else {
        hookNet();
        hookClicks();
        document.addEventListener('DOMContentLoaded', function () { setTimeout(merge, 600); });
        window.addEventListener('load', function () { setTimeout(merge, 1200); });
      }
    })();
    """.trimIndent()
}
