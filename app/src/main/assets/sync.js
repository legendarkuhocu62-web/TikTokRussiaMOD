(function () {
  if (window.__wp) return;
  var v = null, mute = false, pending = null;
  function pick() {
    var a = [].slice.call(document.querySelectorAll('video'));
    a.sort(function (x, y) { return y.clientWidth * y.clientHeight - x.clientWidth * x.clientHeight; });
    return a[0] || null;
  }
  function seek(t) {
    if (location.host.indexOf('netflix') > -1) { // у Netflix прямой currentTime ломает плеер
      try {
        var p = netflix.appContext.state.playerApp.getAPI().videoPlayer;
        p.getVideoPlayerBySessionId(p.getAllPlayerSessionIds()[0]).seek(t * 1000); return;
      } catch (e) {}
    }
    v.currentTime = t;
  }
  function apply(m) {
    mute = true;
    if (Math.abs(v.currentTime - m.t) > 1.5) seek(m.t);
    if (m.p && !v.paused) v.pause();
    if (!m.p && v.paused) { var r = v.play(); if (r && r.catch) r.catch(function () {}); }
    setTimeout(function () { mute = false; }, 1200);
  }
  function bind(el) {
    if (v === el) return; v = el;
    ['play', 'pause', 'seeked'].forEach(function (e) {
      el.addEventListener(e, function () {
        if (mute) return;
        Android.send(JSON.stringify({ a: 'st', p: el.paused, t: el.currentTime }));
      });
    });
    if (pending) { apply(pending); pending = null; }
  }
  setInterval(function () { var e = pick(); if (e) bind(e); }, 1000);
  window.__wp = { apply: function (m) { if (v) apply(m); else pending = m; } };
})();
