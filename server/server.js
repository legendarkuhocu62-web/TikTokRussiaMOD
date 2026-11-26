const http = require('http'), crypto = require('crypto');
const { WebSocketServer } = require('ws');
const rooms = new Map();
const getRoom = id => rooms.get(id) || rooms.set(id, { clients: new Map(), nav: null, st: null, host: null }).get(id);
const j = (ws, o) => ws.readyState === 1 && ws.send(JSON.stringify(o));

const srv = http.createServer((req, res) => {
  if (req.url === '/create') { const id = crypto.randomBytes(4).toString('hex'); getRoom(id); return res.end(id); }
  const m = req.url.match(/^\/r\/(\w+)/);
  if (m) {
    res.setHeader('Content-Type', 'text/html;charset=utf-8');
    return res.end(`<meta name=viewport content="width=device-width"><body style="font:20px sans-serif;text-align:center;padding:40px">
<h3>Комната ${m[1]}</h3><a href="watchparty://join/${m[1]}">Открыть в приложении</a>
<script>location='watchparty://join/${m[1]}'</script>`);
  }
  res.end('ok');
});

new WebSocketServer({ server: srv }).on('connection', ws => {
  const me = crypto.randomBytes(3).toString('hex'); let room;
  j(ws, { a: 'hello', id: me });
  ws.on('message', d => {
    let m; try { m = JSON.parse(d); } catch { return; }
    if (m.a === 'join') {
      room = getRoom(String(m.room)); room.clients.set(me, ws);
      if (room.nav) j(ws, room.nav);
      if (room.st) j(ws, room.st);
      if (room.host) j(ws, { a: 'share', host: room.host });
      return;
    }
    if (!room) return;
    if (m.a === 'sig') { const t = room.clients.get(m.to); if (t) j(t, { a: 'sig', from: me, d: m.d }); return; }
    if (m.a === 'share') {
      room.host = m.on ? me : (room.host === me ? null : room.host);
      for (const [id, c] of room.clients) if (id !== me) j(c, { a: 'share', host: room.host });
      return;
    }
    if (m.a === 'nav') { room.nav = m; room.st = null; } else room.st = m;
    for (const [id, c] of room.clients) if (id !== me) j(c, m);
  });
  ws.on('close', () => {
    if (!room) return; room.clients.delete(me);
    if (room.host === me) { room.host = null; for (const c of room.clients.values()) j(c, { a: 'share', host: null }); }
  });
});
srv.listen(process.env.PORT || 8080);
