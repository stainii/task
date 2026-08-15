import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { connect } from 'node:net';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

import { serve } from './serve.mjs';

/**
 * The real stack, brought up by one command.
 *
 * `docs/quality-bar.md` §4 requires the end-to-end suite to be runnable by hand when you touch a
 * journey, and `docs/ci.md` §4 states the split deliberately: CI supplies Node, browsers, a JDK, a
 * Docker daemon and a built jar, and **bringing the stack up belongs here**, next to the tests. A
 * stack that only exists inside a workflow file is a stack you cannot debug.
 *
 * Everything this starts is the real thing: Postgres and Keycloak from `task-back-end/compose.yaml`
 * — the same containers development uses — the back end's own jar, and the production front-end
 * bundle behind `serve.mjs`. Nothing here is a mock. That is the whole justification for the suite
 * existing (#64): `setOffline(true)` against a real server is the only mechanism in the stack that
 * can test ADR-0004's offline contract end to end.
 *
 * ## What it deliberately does not do
 *
 * **It does not clean the database.** The containers are left running when the suite ends, so a
 * rerun takes seconds instead of waiting for Keycloak's realm import again. The tests are written
 * for that: #10 found an Instancio-minted patch dated 2071 leaking between test classes, and the
 * rule it produced is the rule here — *assert only on data you created, by id*, and never mint a
 * future-dated task. To start from nothing, run `docker compose -f task-back-end/compose.yaml down
 * -v` yourself.
 */

const here = dirname(fileURLToPath(import.meta.url));
const frontEnd = resolve(here, '..');
const repo = resolve(frontEnd, '..');
const backEnd = join(repo, 'task-back-end');

const APP_PORT = 4200;
const BACK_END_PORT = 8080;
const KEYCLOAK_PORT = 8081;
const POSTGRES_PORT = 61655;

/**
 * The realm the browser logs in to, addressed **through the app's own origin**.
 *
 * `application.yml` names `localhost:8081` because that is where development reaches Keycloak
 * directly. Here the issuer is the proxied address, so the token the browser obtains and the token
 * the back end validates carry the same `iss` — and `check-sso` is a first-party request. See
 * `serve.mjs` for why one origin is not optional.
 */
const ISSUER_URI = `http://localhost:${APP_PORT}/realms/stijnhooft-realm`;

/**
 * How long the server keeps one stream open, shrunk from the contract's 15–30 minutes.
 *
 * `application.yml` calls this "a knob only because the tests have to shrink it", and this is that
 * case: a suite that cannot outlive one connection can never see the reconnect, and reconnect-and-
 * resume is the mechanism ADR-0004 calls the most load-bearing and least observable in the whole
 * contract. Ten seconds keeps `stream-resume.spec.ts` inside its timeout while running the real
 * bounded-lifetime close, rather than a simulation of one.
 */
const SSE_LIFETIME = '10s';

const children = [];

async function main() {
  process.on('SIGINT', () => shutdown(130));
  process.on('SIGTERM', () => shutdown(143));

  await compose();
  await waitForPort(POSTGRES_PORT, 'Postgres');
  await waitForOk(
    `http://localhost:${KEYCLOAK_PORT}/realms/stijnhooft-realm/.well-known/openid-configuration`,
    'Keycloak',
  );

  await serve({
    port: APP_PORT,
    root: join(frontEnd, 'dist', 'task-front-end', 'browser'),
    proxies: { '/api': BACK_END_PORT, '/realms': KEYCLOAK_PORT },
  });
  console.log(`The front-end bundle is served on http://localhost:${APP_PORT}.`);

  await backEndJar();
  await waitForOk(`http://localhost:${APP_PORT}/api/config`, 'the back end');

  console.log('The stack is up.');
}

/**
 * Postgres and Keycloak, from the compose file development already uses.
 *
 * `up -d` on containers that are already running is a no-op, which is what makes a rerun cheap.
 */
function compose() {
  return run('docker', ['compose', '-f', join(backEnd, 'compose.yaml'), 'up', '-d'], { cwd: repo });
}

/**
 * The back end, as the jar a deploy would run.
 *
 * The datasource is passed in rather than derived: `spring-boot-docker-compose` is on the runtime
 * classpath and would otherwise try to manage the containers this script has just started, from a
 * working directory that has no compose file. `application.yml` states why there is no datasource
 * block to inherit.
 */
async function backEndJar() {
  const jar = process.env['TASK_BACK_END_JAR'] ?? (await defaultJar());
  console.log(`Starting ${jar}.`);
  children.push(
    spawn('java', [`-Dtask.sse.connection-lifetime=${SSE_LIFETIME}`, '-jar', jar], {
      cwd: backEnd,
      stdio: 'inherit',
      env: {
        ...process.env,
        SPRING_DOCKER_COMPOSE_ENABLED: 'false',
        SPRING_DATASOURCE_URL: `jdbc:postgresql://localhost:${POSTGRES_PORT}/mydatabase`,
        SPRING_DATASOURCE_USERNAME: 'myuser',
        SPRING_DATASOURCE_PASSWORD: 'secret',
        SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI: ISSUER_URI,
        LOG_DIR: join(backEnd, 'target', 'e2e-logs'),
      },
    }),
  );
}

/**
 * The jar `./mvnw package` leaves behind, built first if it is not there.
 *
 * CI hands the path in as `TASK_BACK_END_JAR` and never reaches this. On a laptop, building it is
 * the friendlier answer than an error telling you to run the command yourself — the suite is meant
 * to be runnable by hand.
 */
async function defaultJar() {
  const jar = join(backEnd, 'target', 'task-back-end-0.0.1-SNAPSHOT.jar');
  if (!existsSync(jar)) {
    console.log('No jar yet; building one.');
    await run('./mvnw', ['-B', '-DskipTests', 'package'], { cwd: backEnd });
  }
  return jar;
}

/** Runs a command to completion, rejecting on a non-zero exit. */
function run(command, args, options) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: 'inherit', ...options });
    child.on('error', reject);
    child.on('exit', (code) =>
      code === 0
        ? resolve()
        : reject(new Error(`${command} ${args.join(' ')} exited with ${String(code)}.`)),
    );
  });
}

/** Waits until something is listening, so the next step's failure is its own and not a race. */
async function waitForPort(port, what) {
  await until(
    () =>
      new Promise((resolve) => {
        const socket = connect({ host: '127.0.0.1', port }, () => {
          socket.end();
          resolve(true);
        });
        socket.on('error', () => resolve(false));
      }),
    what,
  );
}

/** Waits until a URL answers `2xx`. */
async function waitForOk(url, what) {
  await until(async () => {
    try {
      return (await fetch(url)).ok;
    } catch {
      return false;
    }
  }, what);
}

/**
 * Polls until a condition holds, and gives up loudly.
 *
 * Two minutes: Keycloak's realm import on a cold container is the slow one, and a timeout that
 * fires under it would make a working stack look broken on the first run of the day.
 */
async function until(condition, what, timeoutMs = 120_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    if (await condition()) {
      console.log(`${what} is up.`);
      return;
    }
    if (Date.now() > deadline) {
      throw new Error(`${what} did not come up within ${String(timeoutMs / 1000)}s.`);
    }
    await delay(500);
  }
}

/**
 * Stops what this script started, and leaves the containers alone.
 *
 * The jar is a child process rather than a container, so nothing else would ever stop it: a run
 * that left one behind would hold port 8080 and the next run would silently test the previous
 * build.
 */
function shutdown(code) {
  for (const child of children) {
    child.kill('SIGTERM');
  }
  process.exit(code);
}

await main();
