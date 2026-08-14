/**
 * A static file server, for the checks that cannot honestly run over `file://`.
 *
 * The Vaults playtest opens the page directly off disk and that is fine — it only needs a canvas
 * and a clock. The daily needs more than that: Chromium refuses `localStorage` on a `file://`
 * origin, and the clipboard API is unavailable outside a secure context. Both are things the
 * daily is *made of* — a streak that does not survive a refresh is not a streak — so the test
 * has to meet them where a player does, on http://127.0.0.1, which counts as trustworthy.
 *
 * Node's own http module, no dependency, no configuration.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

/** Serves *dir* on a free port, resolving to `{ url, close }`. */
function serve(dir) {
  const root = path.resolve(dir);
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html';
    // Resolve first, then check containment: `..` in a URL must not reach outside the site.
    const file = path.resolve(root, rel);
    if (!file.startsWith(root + path.sep) && file !== root) {
      res.writeHead(403).end('no');
      return;
    }
    fs.readFile(file, (err, body) => {
      if (err) { res.writeHead(404).end('not found'); return; }
      res.writeHead(200, { 'content-type': TYPES[path.extname(file)] || 'application/octet-stream' });
      res.end(body);
    });
  });
  return new Promise((resolve, reject) => {
    server.on('error', reject);
    server.listen(0, '127.0.0.1', () => resolve({
      url: `http://127.0.0.1:${server.address().port}`,
      close: () => new Promise(done => server.close(done)),
    }));
  });
}

module.exports = { serve };
