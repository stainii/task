import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer, request as httpRequest } from 'node:http';
import { extname, join, normalize, sep } from 'node:path';

/**
 * The stand-in for nginx: one origin serving the built front-end, with `/api` and `/realms`
 * proxied behind it.
 *
 * This exists because the end-to-end suite cannot use `ng serve`. Two of the four scenarios in
 * [#64](https://github.com/stainii/task/issues/64) need the *production* artefact — a cold start
 * offline is served by `ngsw-worker.js`, which the dev server does not emit — so the suite runs
 * against `dist/`, exactly the bundle a deploy would ship.
 *
 * **One origin is the load-bearing part**, not a convenience. ADR-0010 puts the app and Keycloak
 * behind a single nginx allowlist in production, and `src/proxy.conf.json` mirrors that for
 * development. Same-origin is what makes the silent `check-sso` iframe a first-party request, and
 * `auth.ts` sets `silentCheckSsoFallback: false` — so on separate origins a reload would simply
 * never recover its session, and the suite would be testing a shape the deployed app does not have.
 *
 * The `Host` header is forwarded untouched, which is how Keycloak learns it is addressed as
 * `localhost:4200` and mints tokens whose `iss` says so. `stack.mjs` points the back end's
 * `issuer-uri` at the same address, so both halves agree on one issuer.
 */

const TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.map', 'application/json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
  ['.txt', 'text/plain; charset=utf-8'],
  ['.webmanifest', 'application/manifest+json'],
  ['.woff2', 'font/woff2'],
]);

/**
 * Files a client must never be handed a stale copy of.
 *
 * `ngsw.json` is the service worker's manifest — cache it and the worker keeps installing the build
 * before last, which is precisely the half-landed deploy ADR-0009's second banner exists to report.
 */
const NEVER_CACHE = new Set(['/index.html', '/ngsw.json', '/ngsw-worker.js', '/safety-worker.js']);

/**
 * Starts the server and resolves once it is listening.
 *
 * @param {{ port: number, root: string, proxies: Record<string, number> }} options
 *   `proxies` maps a path prefix onto the localhost port that serves it.
 * @returns {Promise<import('node:http').Server>}
 */
export function serve({ port, root, proxies }) {
  const prefixes = Object.entries(proxies);

  const server = createServer((req, res) => {
    const path = new URL(req.url ?? '/', 'http://localhost').pathname;
    const proxied = prefixes.find(([prefix]) => path === prefix || path.startsWith(`${prefix}/`));
    if (proxied !== undefined) {
      proxy(req, res, proxied[1]);
      return;
    }
    void staticFile(req, res, root, path);
  });

  return new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, () => resolve(server));
  });
}

/**
 * Hands the request to a local port and streams the answer straight back.
 *
 * Nothing is buffered, because the stream is an `SseEmitter`: a proxy that waits for a complete
 * body delivers ADR-0004's patches in one lump at disconnect, which looks exactly like sync being
 * broken. It is the same failure ADR-0007 warns nginx about with `proxy_buffering off`.
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {number} port
 */
function proxy(req, res, port) {
  const upstream = httpRequest(
    { host: '127.0.0.1', port, method: req.method, path: req.url, headers: req.headers },
    (answer) => {
      const headers = { ...answer.headers };
      // Node frames the response itself; passing these through would describe a framing that is no
      // longer true.
      delete headers['transfer-encoding'];
      delete headers['connection'];
      res.writeHead(answer.statusCode ?? 502, headers);
      answer.pipe(res);
    },
  );

  upstream.on('error', () => {
    // The back end being down is an ordinary state for this app to be in — half the suite is about
    // it — so it is answered, not thrown.
    if (!res.headersSent) {
      res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    }
    res.end('The upstream is not answering.');
  });

  // A client abandoning a stream must not leave the upstream connection open behind it.
  res.on('close', () => upstream.destroy());
  req.pipe(upstream);
}

/**
 * Serves a file out of the build, falling back to `index.html` so a deep link works.
 *
 * The fallback is deliberately restricted to requests that could be a route. A missing asset that
 * answered with `index.html` would hand the service worker a page where it asked for a bundle, and
 * the failure would surface as a parse error a long way from its cause.
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {string} root
 * @param {string} path
 */
async function staticFile(req, res, root, path) {
  const requested = await resolveFile(root, path);
  const file = requested ?? (extname(path) === '' ? join(root, 'index.html') : null);
  if (file === null) {
    res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Not found.');
    return;
  }

  const served = requested === null ? '/index.html' : path;
  res.writeHead(200, {
    'content-type': TYPES.get(extname(file)) ?? 'application/octet-stream',
    'cache-control': NEVER_CACHE.has(served) ? 'no-cache' : 'public, max-age=3600',
  });
  if (req.method === 'HEAD') {
    res.end();
    return;
  }
  createReadStream(file).pipe(res);
}

/**
 * The file a path names, or null if there is none inside the build.
 *
 * @param {string} root
 * @param {string} path
 * @returns {Promise<string | null>}
 */
async function resolveFile(root, path) {
  const candidate = join(root, normalize(path));
  if (candidate !== root && !candidate.startsWith(root + sep)) {
    return null;
  }
  try {
    return (await stat(candidate)).isFile() ? candidate : null;
  } catch {
    return null;
  }
}
