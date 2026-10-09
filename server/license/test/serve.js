// Serves the real Code.gs over HTTP the way Google Apps Script does, for the end-to-end test of the app's
// license client: POST /macros/s/test/exec runs doPost and answers "302 Found" to a one-time URL that
// returns the JSON (exactly like script.google.com -> script.googleusercontent.com).
//   node server/license/test/serve.js   -> prints "PORT <n>" when ready
// Test-only admin endpoints (/admin/...) stand in for the seller's sheet menu.
'use strict';
const http = require('http');
const crypto = require('crypto');
const { loadServer } = require('./fake-gas');

const { gs, sheet, keys } = loadServer();
gs.setupSheet_();
const results = new Map();

function send(res, status, body, type = 'application/json; charset=utf-8', headers = {}) {
  res.writeHead(status, { 'Content-Type': type, ...headers });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const body = await readBody(req);
  try {
    if (url.pathname === '/macros/s/test/exec') {
      const out = req.method === 'POST' ? gs.doPost({ postData: { contents: body } }) : gs.doGet({});
      const id = crypto.randomUUID();
      results.set(id, out.getContent());
      return send(res, 302, '<html>Moved</html>', 'text/html', { Location: `http://127.0.0.1:${server.address().port}/macros/echo?id=${id}` });
    }
    if (url.pathname === '/macros/echo' && req.method === 'GET') {
      const content = results.get(url.searchParams.get('id'));
      results.delete(url.searchParams.get('id'));
      return content ? send(res, 200, content) : send(res, 404, 'gone', 'text/plain');
    }
    if (url.pathname === '/filtered') {
      // What a content filter typically answers instead of the real server.
      return send(res, 403, '<html dir="rtl"><body>האתר חסום על ידי מערכת הסינון</body></html>', 'text/html; charset=utf-8');
    }
    if (url.pathname === '/broken') return send(res, 500, 'Internal error', 'text/plain');
    const p = body ? JSON.parse(body) : {};
    const row = () => gs.findRow_(sheet(), gs.normUser_(p.username));
    if (url.pathname === '/admin/add') return send(res, 200, JSON.stringify(gs.createCustomer_(sheet(), p.username, '', new Date())));
    if (url.pathname === '/admin/release') { gs.releaseDevice_(sheet(), row().index); return send(res, 200, '{}'); }
    if (url.pathname === '/admin/status') { sheet().getRange(row().index, gs.COL.STATUS).setValue(p.status); return send(res, 200, '{}'); }
    send(res, 404, 'not found', 'text/plain');
  } catch (e) {
    send(res, 500, String(e && e.stack || e), 'text/plain');
  }
});

server.listen(0, '127.0.0.1', () => console.log(`PORT ${server.address().port} PUBKEY ${keys.publicDer}`));
