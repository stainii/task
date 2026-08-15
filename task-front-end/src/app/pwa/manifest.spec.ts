import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * The web app manifest, read as a specification.
 *
 * Installability is not decoration here — it is the mechanism behind
 * [#55](https://github.com/stainii/task/issues/55)'s `navigator.storage.persist()`. An installed
 * origin is exempt from Safari's seven-day eviction; a bookmarked tab is not. So a manifest that
 * quietly stops qualifying takes the durability of the outbox with it, and the only symptom is a
 * week of queued work disappearing on a device nobody was watching.
 *
 * The icon assertions exist because a manifest pointing at a file that is not there still parses,
 * still serves, and simply is not installable. Nothing in the build says so.
 */

const MANIFEST = resolve(process.cwd(), 'public', 'manifest.webmanifest');
const PUBLIC = resolve(process.cwd(), 'public');

interface Icon {
  readonly src: string;
  readonly sizes: string;
  readonly type: string;
  readonly purpose?: string;
}

interface Manifest {
  readonly name: string;
  readonly short_name: string;
  readonly start_url: string;
  readonly scope: string;
  readonly display: string;
  readonly theme_color: string;
  readonly background_color: string;
  readonly icons: readonly Icon[];
}

const manifest = JSON.parse(readFileSync(MANIFEST, 'utf8')) as Manifest;

/**
 * Width and height straight out of the PNG's IHDR chunk — bytes 16..24 of any PNG. Read from the
 * file rather than trusted from the manifest, because the whole point of the check is that the two
 * can disagree.
 */
function pixelSize(file: string): string {
  const png = readFileSync(resolve(PUBLIC, file.replace(/^\//, '')));
  return `${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`;
}

describe('manifest.webmanifest', () => {
  it('is installable as a standalone app', () => {
    expect(manifest.display).toBe('standalone');
    expect(manifest.start_url).toBe('/');
    expect(manifest.scope).toBe('/');
    expect(manifest.name).toBeTruthy();
    expect(manifest.short_name).toBeTruthy();
  });

  it('declares icons that exist, at the size they claim', () => {
    expect(manifest.icons.length).toBeGreaterThan(0);

    for (const icon of manifest.icons) {
      expect(pixelSize(icon.src), `${icon.src} is not ${icon.sizes}`).toBe(icon.sizes);
      expect(icon.type).toBe('image/png');
    }
  });

  it('carries the two sizes an install prompt requires, plus a maskable icon', () => {
    // 192 and 512 are the pair Chrome checks for before it will offer installation at all. The
    // maskable variant is what stops Android cropping the glyph inside its own circle.
    const any = manifest.icons.filter((icon) => (icon.purpose ?? 'any').split(' ').includes('any'));
    expect(any.map((icon) => icon.sizes)).toEqual(expect.arrayContaining(['192x192', '512x512']));

    const maskable = manifest.icons.filter((icon) =>
      (icon.purpose ?? '').split(' ').includes('maskable'),
    );
    expect(maskable.length).toBeGreaterThan(0);
  });

  it('paints its splash in the colours the app already uses', () => {
    // The values are the ones `styles.css` resolves to, so the launch screen is not a white flash
    // in front of a dark app.
    expect(manifest.theme_color).toBe('#3f51b5');
    expect(manifest.background_color).toBe('#f6f6f8');
  });
});
