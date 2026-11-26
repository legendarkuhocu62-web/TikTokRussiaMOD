(function () {
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

  // Раз в 5 секунд сообщаем позицию — приложение решает, рассылать ли её.
  setInterval(function () {
    var v = mainVideo();
    if (v && !v.paused && Date.now() > suppressUntil) post({ k: 'pos', time: v.currentTime });
  }, 5000);
})();
