#!/usr/bin/env bash
#
# Regenerates every icon in `public/` from the three SVGs next to this script (#62).
#
# The icons are drawn, not ported: the artwork is ADR-0019's own `complete` glyph — the exact path
# string from `src/app/ui/glyph.ts` — in white on `--app-accent`. This script exists so that
# "regenerated" stays a command rather than becoming a design session the next time a platform
# wants a size nobody has.
#
# Needs `rsvg-convert` (librsvg) and `magick` (ImageMagick): brew install librsvg imagemagick
set -euo pipefail

cd "$(dirname "$0")/.."
mkdir -p public/icons

# The pair an install prompt checks for. Rounded square, glyph full size.
for size in 192 512; do
  rsvg-convert -w "$size" -h "$size" icons/icon-any.svg -o "public/icons/icon-${size}.png"
done

# Maskable is a *different drawing*, not the same file relabelled: full bleed, no rounded corners,
# glyph at 62% so Android can crop to a circle or a squircle without clipping the tick.
for size in 192 512; do
  rsvg-convert -w "$size" -h "$size" icons/icon-maskable.svg -o "public/icons/icon-maskable-${size}.png"
done

# iOS ignores the manifest's icons entirely and reads this one — and iOS is the platform where
# installing is what stops the store being evicted after seven days.
rsvg-convert -w 180 -h 180 icons/icon-any.svg -o public/icons/apple-touch-icon.png

# The favicon carries a heavier stroke and a tighter radius: the production drawing turns to mush
# at 16 pixels.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
for size in 16 32 48; do
  rsvg-convert -w "$size" -h "$size" icons/icon-small.svg -o "$tmp/favicon-${size}.png"
done
magick "$tmp/favicon-16.png" "$tmp/favicon-32.png" "$tmp/favicon-48.png" public/favicon.ico
