(function () {
  // Внутри официального плеера YouTube синхронизацией занимается его собственный API (yt.html).
  if (window.__coYt) return;
  try {
    if (/(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname) && /^\/embed\//.test(location.pathname)) return;
  } catch (e) {}
  if (window.__coInstalled) return;
  window.__coInstalled = true;

  var suppressUntil = 0;
  var lastCmd = null, lastCmdAt = 0, lastApplied = true, pollT = null;
  var MIN_AREA = 6000; // игнорируем маленькие превью-ролики

  function post(o) {
    try {
      var s = JSON.stringify(o);
      if (window.CoBridge && window.CoBridge.postMessage) window.CoBridge.postMessage(s);
      else if (window.CoNative && window.CoNative.postEvent) window.CoNative.postEvent(s);
    } catch (e) {}
  }

  function area(v) {
    try { var r = v.getBoundingClientRect(); return r.width * r.height; } catch (e) { return 0; }
  }

  function mainVideo() {
    var vs = document.getElementsByTagName('video'), best = null, ba = 0;
    for (var i = 0; i < vs.length; i++) {
      var a = area(vs[i]);
      if (a > ba) { ba = a; best = vs[i]; }
    }
    return ba >= MIN_AREA ? best : null;
  }

  function relay(msg) {
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage(msg, '*'); } catch (e) {}
    }
  }

  function safePlay(v) {
    try {
      var p = v.play();
      if (p && p.catch) p.catch(function () { post({ k: 'blocked' }); });
    } catch (e) {}
  }

  function tryApply() {
    if (!lastCmd || lastApplied) return;
    var age = (Date.now() - lastCmdAt) / 1000;
    if (age > 60) { lastCmd = null; lastApplied = true; return; }
    var v = mainVideo();
    if (!v) { schedulePoll(); return; }
    var c = lastCmd;
    var t = (c.time || 0) + ((c.a === 'play' || c.a === 'tick') ? age : 0);
    suppressUntil = Date.now() + 1500;
    if (c.a === 'pause') {
      v.pause();
      if (Math.abs(v.currentTime - t) > 0.5) v.currentTime = t;
    } else if (c.a === 'play') {
      if (Math.abs(v.currentTime - t) > 0.7) v.currentTime = t;
      safePlay(v);
    } else if (c.a === 'seek') {
      v.currentTime = t;
    } else if (c.a === 'tick') {
      if (Math.abs(v.currentTime - t) > 2) v.currentTime = t;
      if (v.paused) safePlay(v);
    }
    lastApplied = true;
  }

  function schedulePoll() {
    if (pollT) return;
    pollT = setInterval(function () {
      if (!lastCmd || lastApplied) { clearInterval(pollT); pollT = null; return; }
      tryApply();
    }, 700);
  }

  function handle(c) {
    lastCmd = c; lastCmdAt = Date.now(); lastApplied = false;
    relay({ __co: 1, cmd: c });
    tryApply();
  }

  window.__coCmd = handle;

  window.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__co === 1 && d.cmd) {
      handle(d.cmd);
    } else if (d.__co === 2 && lastCmd && e.source) {
      var age = (Date.now() - lastCmdAt) / 1000;
      var c = { a: lastCmd.a, time: (lastCmd.time || 0) + ((lastCmd.a === 'play' || lastCmd.a === 'tick') ? age : 0) };
      try { e.source.postMessage({ __co: 1, cmd: c }, '*'); } catch (x) {}
    }
  }, false);

  // Поздно созданный iframe спрашивает у родителя последнюю команду.
  if (window !== window.top) {
    try { window.parent.postMessage({ __co: 2 }, '*'); } catch (e) {}
  }

  function onMedia(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (Date.now() < suppressUntil) return;
    if (area(v) < MIN_AREA) return;
    if (e.type === 'pause' && (v.ended || v.seeking)) return;
    var a = e.type === 'play' ? 'play' : (e.type === 'pause' ? 'pause' : 'seek');
    post({ k: 'media', a: a, time: v.currentTime });
  }
  ['play', 'pause', 'seeked'].forEach(function (n) { document.addEventListener(n, onMedia, true); });

  // Появился новый плеер (или его запустили): просим приложение подтянуть его к состоянию комнаты.
  var seen = typeof WeakSet === 'function' ? new WeakSet() : null;
  function onReadyEv(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (area(v) < MIN_AREA) return;
    if (seen) { if (seen.has(v)) return; seen.add(v); }
    post({ k: 'ready' });
  }
  ['loadedmetadata', 'playing', 'play'].forEach(function (n) { document.addEventListener(n, onReadyEv, true); });

  // ===== Зеркалирование интерфейса: прокрутка, нажатия, ввод (только верхний документ) =====
  if (window === window.top) {
    var uiSuppress = 0, scrollT = null;

    var cssPath = function (el) {
      var parts = [];
      while (el && el.nodeType === 1 && el !== document.documentElement) {
        var part = el.nodeName.toLowerCase();
        if (el.id && /^[A-Za-z][\w-]*$/.test(el.id) && document.querySelectorAll('#' + el.id).length === 1) {
          parts.unshift('#' + el.id);
          break;
        }
        var idx = 1, sib = el;
        while ((sib = sib.previousElementSibling)) { if (sib.nodeName === el.nodeName) idx++; }
        parts.unshift(part + ':nth-of-type(' + idx + ')');
        el = el.parentElement;
      }
      return parts.join(' > ');
    };

    var scrollRatio = function () {
      var d = document.documentElement;
      var max = Math.max(1, (d.scrollHeight || 0) - window.innerHeight);
      var y = window.pageYOffset || d.scrollTop || 0;
      return Math.min(1, Math.max(0, y / max));
    };

    var ripple = function (x, y) {
      try {
        var d = document.createElement('div');
        d.style.cssText = 'position:fixed;z-index:2147483647;pointer-events:none;width:46px;height:46px;' +
          'margin:-23px 0 0 -23px;border-radius:50%;border:3px solid #a78bfa;background:rgba(124,58,237,.35);' +
          'left:' + x + 'px;top:' + y + 'px;transition:transform .55s ease-out,opacity .55s ease-out;' +
          'transform:scale(.4);opacity:1';
        (document.body || document.documentElement).appendChild(d);
        requestAnimationFrame(function () {
          requestAnimationFrame(function () { d.style.transform = 'scale(1.7)'; d.style.opacity = '0'; });
        });
        setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 800);
      } catch (e) {}
    };

    // --- управляющий: отправляет ---
    window.addEventListener('scroll', function () {
      if (!window.__coCtl || Date.now() < uiSuppress || scrollT) return;
      scrollT = setTimeout(function () {
        scrollT = null;
        post({ k: 'ui', e: 'scroll', y: scrollRatio() });
      }, 120);
    }, { passive: true });

    document.addEventListener('click', function (e) {
      if (!window.__coCtl || !e.isTrusted) return;
      post({ k: 'ui', e: 'click', sel: cssPath(e.target), nx: e.clientX / window.innerWidth, ny: e.clientY / window.innerHeight });
    }, true);

    document.addEventListener('input', function (e) {
      if (!window.__coCtl || !e.isTrusted) return;
      var t = e.target;
      if (!t || !/^(INPUT|TEXTAREA)$/.test(t.tagName)) return;
      var ty = (t.type || '').toLowerCase();
      if (ty === 'password' || ty === 'file') return;
      var ac = (t.getAttribute('autocomplete') || '').toLowerCase();
      if (ac.indexOf('cc-') === 0 || ac.indexOf('password') >= 0) return;
      post({ k: 'ui', e: 'input', sel: cssPath(t), val: String(t.value).slice(0, 500) });
    }, true);

    // --- остальные: повторяют ---
    window.__coUi = function (d) {
      if (!d) return;
      try {
        if (d.e === 'scroll') {
          var dd = document.documentElement;
          var max = Math.max(0, (dd.scrollHeight || 0) - window.innerHeight);
          uiSuppress = Date.now() + 500;
          window.scrollTo(0, Math.round((d.y || 0) * max));
        } else if (d.e === 'click') {
          var x = d.nx * window.innerWidth, y = d.ny * window.innerHeight;
          ripple(x, y);
          var el = null;
          try { el = d.sel ? document.querySelector(d.sel) : null; } catch (err) {}
          if (!el) el = document.elementFromPoint(x, y);
          if (!el) return;
          // Переходы по ссылкам повторяет синхронизация адреса, чтобы не открывать дважды.
          var a = el.closest ? el.closest('a[href]') : null;
          if (a && /^https?:/i.test(a.href)) return;
          el.click();
        } else if (d.e === 'input') {
          var f = null;
          try { f = document.querySelector(d.sel); } catch (err) {}
          if (f && 'value' in f) {
            var proto = f.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
            var desc = Object.getOwnPropertyDescriptor(proto, 'value');
            if (desc && desc.set) desc.set.call(f, d.val); else f.value = d.val;
            f.dispatchEvent(new Event('input', { bubbles: true }));
            f.dispatchEvent(new Event('change', { bubbles: true }));
          }
        }
      } catch (err) {}
    };
  }

  // Сообщаем приложению, какое видео играет (адрес источника), чтобы зритель включил тот же поток.
  function reportSrc(v) {
    var d = v.duration;
    if (isFinite(d) && d < 90) return; // рекламу и превью пропускаем
    post({ k: 'src', url: v.currentSrc || v.src || '', dur: isFinite(d) ? d : 0, page: location.href });
  }
  ['loadedmetadata', 'durationchange'].forEach(function (n) {
    document.addEventListener(n, function (e) {
      var v = e.target;
      if (!v || v.tagName !== 'VIDEO' || area(v) < MIN_AREA) return;
      reportSrc(v);
    }, true);
  });

  // Раз в 5 секунд сообщаем позицию — приложение решает, рассылать ли её.
  setInterval(function () {
    var v = mainVideo();
    if (v && !v.paused && Date.now() > suppressUntil) post({ k: 'pos', time: v.currentTime });
  }, 5000);
})();
