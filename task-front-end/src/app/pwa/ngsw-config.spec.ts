import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * `ngsw-config.json` read as a specification rather than as settings.
 *
 * [#62](https://github.com/stainii/task/issues/62) is the ticket where the service worker arrives,
 * and its whole argument is that **the `?since=` defect can be reintroduced by configuration
 * instead of by code**. Portal cached five `/api/**` paths with `strategy: freshness` because it
 * had no offline store; ADR-0004 replaced that with IndexedDB, an ordered outbox and a shared fold.
 * `ngsw` caches GET, and the resync path *is* a GET — so a cached snapshot hands the client a stale
 * `sequence` watermark, the stream resumes from the wrong point, and the patches in the gap are
 * lost permanently and invisibly.
 *
 * Nothing else in the stack can see that. It is not a code path, it is a JSON file, and the only
 * thing standing between it and the real database is this test.
 */

const CONFIG = resolve(process.cwd(), 'ngsw-config.json');
const SCHEMA = 'node_modules/@angular/service-worker/config/schema.json';

interface NgswConfig {
  readonly appData?: unknown;
  readonly index: string;
  readonly assetGroups?: readonly {
    readonly name: string;
    readonly resources: { readonly urls?: readonly string[]; readonly files?: readonly string[] };
  }[];
  readonly dataGroups?: readonly { readonly urls?: readonly string[] }[];
}

interface SchemaShape {
  readonly additionalProperties: boolean;
  readonly properties: Record<string, unknown>;
}

const raw = readFileSync(CONFIG, 'utf8');

/**
 * The build reads this file with `JSON.parse` (`@angular/build/src/utils/service-worker.js`), so
 * the explanation the ticket asks for cannot be a `//` comment — it has to be a key. Which key is
 * not free either: the schema sets `additionalProperties: false`, so it goes in `appData`, the one
 * free-form slot. This parse is what proves the file is still strict JSON if it is hand-edited.
 */
const config = JSON.parse(raw) as NgswConfig;

/**
 * `ngsw`'s own glob semantics: `**` crosses `/`, a single `*` does not. Written out here rather
 * than imported because `@angular/service-worker/config` does not export it — and a check that
 * asked the config whether it matches itself would pass by construction.
 */
function matches(glob: string, url: string): boolean {
  // One pass, so the replacement for `**` can never itself be rewritten by a later pass — which is
  // how a chain of `replace` calls quietly stops matching what it claims to.
  const pattern = glob.replace(/\*\*|[*?]|[.+^${}()|[\]\\]/g, (token) => {
    if (token === '**') return '.*';
    if (token === '*') return '[^/]*';
    if (token === '?') return '.';
    return `\\${token}`;
  });
  return new RegExp(`^${pattern}$`).test(url);
}

/** Every URL pattern in the file, from both group kinds — `assetGroups` can carry `urls` too. */
function urlPatterns(): string[] {
  return [
    ...(config.assetGroups ?? []).flatMap((group) => group.resources.urls ?? []),
    ...(config.dataGroups ?? []).flatMap((group) => group.urls ?? []),
  ];
}

describe('ngsw-config.json', () => {
  it('caches no API response at all', () => {
    expect(config.dataGroups).toEqual([]);
  });

  it('uses no key the ngsw schema rejects', () => {
    // `additionalProperties: false`, so an invented key is not a harmless annotation — the build's
    // own `JSON.parse` tolerates anything, and the disagreement only ever surfaces in an editor or
    // in whatever validates this next. Read from the installed schema rather than listed here, so
    // the check follows the version actually in use.
    const schema = JSON.parse(readFileSync(resolve(process.cwd(), SCHEMA), 'utf8')) as SchemaShape;
    expect(schema.additionalProperties).toBe(false);

    const allowed = Object.keys(schema.properties);
    expect(Object.keys(config).filter((key) => !allowed.includes(key))).toEqual([]);
  });

  it('says in the file itself why dataGroups is empty', () => {
    // An empty array is indistinguishable from an oversight, and the next person to want offline
    // reads will fill it in. The reason has to travel with the file — in `appData`, which is the
    // only free-form key the schema sanctions.
    const reason = JSON.stringify(config.appData);
    expect(reason).toMatch(/dataGroups/);
    expect(reason).toMatch(/sequence|watermark|resync/i);
  });

  it('has a matcher that would actually catch portal', () => {
    // The check below iterates the file's URL patterns, and the file currently has none — so a
    // broken matcher passes it without ever running. This is the guard on the guard: portal's own
    // five `dataGroups` globs, asserted to be recognised. Not theoretical — the first version of
    // `matches` was broken by a stray control character and the suite stayed green.
    expect(
      matches('http*://**/todo/api/**/*', 'https://portal.stijnhooft.be/todo/api/task/1'),
    ).toBe(true);
    expect(matches('/api/**', '/api/task-patches')).toBe(true);
    expect(matches('/*.js', '/main-A1B2C3.js')).toBe(true);

    // And a single `*` must not cross a slash, or every assertion below passes for the wrong reason.
    expect(matches('/*.js', '/nested/main.js')).toBe(false);
    expect(matches('/api/*', '/api/task-patches/1')).toBe(false);
  });

  it('matches no request the sync path makes', () => {
    // Stated as a rule against real URLs rather than as "the array is empty", so that a group added
    // later fails here instead of shipping. `/api/tasks` is ADR-0004's hard-reset snapshot and
    // `/api/task-patches` carries both the resync GET and its `?since=` cursor.
    const syncRequests = [
      '/api/tasks',
      '/api/task-patches',
      '/api/task-patches?since=42',
      'https://task.stijnhooft.be/api/task-patches?since=42',
      '/api/task-templates',
    ];

    for (const glob of urlPatterns()) {
      for (const request of syncRequests) {
        expect(matches(glob, request), `${glob} must not cache ${request}`).toBe(false);
      }
    }
  });

  it('prefetches the app shell, which is what makes the app installable', () => {
    // Cold-starting with the radio off needs the document, the bundles and the styles already on
    // disk. `installMode: prefetch` is the difference between an installed app and a bookmark.
    const shell = (config.assetGroups ?? []).find((group) => 'installMode' in group);
    expect(shell).toMatchObject({ installMode: 'prefetch' });
    expect(config.index).toBe('/index.html');
  });

  it('prefetches the icons the manifest declares', () => {
    // Lazy is the wrong mode for the install artwork: a device that installs and then goes offline
    // before anything happened to request an icon has none on disk, and the icon is the thing the
    // user taps. Read from the manifest rather than listed here, so adding a size cannot leave the
    // caching behind.
    const manifest = JSON.parse(
      readFileSync(resolve(process.cwd(), 'public', 'manifest.webmanifest'), 'utf8'),
    ) as { icons: readonly { src: string }[] };

    const prefetched = (config.assetGroups ?? [])
      .filter((group) => 'installMode' in group && group.resources.files)
      .flatMap((group) => group.resources.files ?? []);

    for (const icon of manifest.icons) {
      expect(
        prefetched.some((glob) => matches(glob, icon.src)),
        `${icon.src} is not prefetched`,
      ).toBe(true);
    }
  });
});
