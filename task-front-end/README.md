# TaskFrontEnd

[![CI](https://github.com/stainii/task/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/stainii/task/actions/workflows/ci.yml)

This project was generated using [Angular CLI](https://github.com/angular/angular-cli) version 21.2.2.

Node is pinned to 26 in `.nvmrc` — run `nvm use` first, or the CLI refuses to start. CI runs
`npm run lint`, `npm run format:check`, `npm test` and `npm run build` on every push; see
`../docs/ci.md`.

Running both stacks together, the ports and the login are in
[`docs/operating-manual.md`](../docs/operating-manual.md); this file is the front-end's own detail.

## Development server

To start a local development server, run:

```bash
ng serve
```

Once the server is running, open your browser and navigate to `http://localhost:4200/`. The application will automatically reload whenever you modify any of the source files.

## Code scaffolding

Angular CLI includes powerful code scaffolding tools. To generate a new component, run:

```bash
ng generate component component-name
```

For a complete list of available schematics (such as `components`, `directives`, or `pipes`), run:

```bash
ng generate --help
```

## Building

To build the project run:

```bash
ng build
```

This will compile your project and store the build artifacts in the `dist/` directory. By default, the production build optimizes your application for performance and speed.

## Running unit tests

To execute unit tests with the [Vitest](https://vitest.dev/) test runner, use the following command:

```bash
npm test
```

**`npm test`, not `ng test`.** The script pins `TZ=Europe/Brussels`, and the zone is part of the
test rather than a detail of it: the band arithmetic is written to survive the two days a year that
are 23 and 25 hours long, and in a zone with no daylight saving those tests pass against the very
bug they exist to catch. See `docs/quality-bar.md` §3.

## The PWA

Installability is not decoration: an installed origin is exempt from Safari's seven-day eviction of
script-writable storage, so it is what makes `navigator.storage.persist()` in `store/local-store.ts`
mean anything. A plain browser tab is a **supported degraded mode**, not a second policy — an evicted
store is not data loss, because ADR-0004's hard-reset path refetches it, and **only an undrained
outbox is**.

**`ngsw-config.json` has an empty `dataGroups`, deliberately.** The file says why and
`src/app/pwa/ngsw-config.spec.ts` enforces it. Short version: `ngsw` caches GET and the resync path
is a GET, so a cached snapshot hands the client a stale `sequence` watermark and the patches in the
gap are lost — the `?since=` defect ADR-0004 exists to kill, reintroduced by configuration rather
than by code. Offline reads come from IndexedDB. Nothing goes in `dataGroups`.

Note that the service worker only exists in a **production** build (`serviceWorker` is set on that
configuration alone), so `ng serve` never has one.

### Icons

Regenerated, not ported from portal. The artwork is
[ADR-0019](../docs/adr/0019-verbs-are-glyphs-facts-are-words.md)'s own `complete` glyph — the exact
path string from `src/app/ui/glyph.ts` — in white on `--app-accent`, which makes the app's icon a
member of its own four-glyph vocabulary rather than a new drawing.

`icon-small.svg`, which produces the favicon, is the **one** redrawing: a heavier stroke on a
slightly shorter path, because the production glyph turns to mush at 16 pixels. It is the same mark,
not the same path string.

The SVG sources live in `icons/`. To regenerate every size:

```bash
./icons/generate.sh
```

RES-015 keeps **one** asset from portal — the _"nothing to do"_ image — for the empty-state redesign
to accept or reject. **It is deliberately not in this repo.** It arrived in portal as stock art with
no licence file, this repo is public by choice
([#31](https://github.com/stainii/task/issues/31)), and committing it is what publishes it. It stays
where it already is, at `portal-front-end/src/assets/todo/imgs/nothing_to_do.png`, until a redesign
actually wants it and its licence has been established. The overview currently shows FE-006's words
alone (`Relax! Nothing else to do.`).

## Running end-to-end tests

```bash
npm run e2e
```

Playwright, against the real stack — Postgres and Keycloak from `../task-back-end/compose.yaml`, the
back-end jar, and this app's **production** bundle behind one origin. `e2e/stack.mjs` starts all of
it, so the command works on a laptop exactly as it does in CI; the containers are left running
afterwards, so a rerun takes seconds. Browsers come from `npx playwright install --with-deps`, once.

Four scenarios, and no more. They are the ones with no other witness: `setOffline(true)` is the only
mechanism in the stack that can test ADR-0004's offline contract end to end, and everything a Vitest
could assert is asserted there instead. `docs/quality-bar.md` §4 lists them and the three rules they
are written to.

`ng serve` is not usable here — it emits no service worker, and half of these scenarios are about
what the worker serves when there is no network.

## Additional Resources

For more information on using the Angular CLI, including detailed command references, visit the [Angular CLI Overview and Command Reference](https://angular.dev/tools/cli) page.
