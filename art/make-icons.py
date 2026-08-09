#!/usr/bin/env python3
"""Generates every launcher icon on both platforms from art/app-icon.png.

ONE MASTER, TWO PLATFORMS. The icon used to be a pair of Android vector
drawables with a script that redrew them for iOS; it is a supplied raster now,
and this turns that one file into everything the two builds need. Replace
app-icon.png, re-run this, commit the outputs.

    pip install Pillow && python3 art/make-icons.py

Why the outputs are committed rather than generated during the build: iOS wants
a PNG in an asset catalogue and Android wants PNGs per density, and neither
build has Pillow. Committing them keeps CI free of a Python step; committing
THIS keeps them reviewable, which a lone binary would not be.

Three things it does that a plain resize would not:

  - CROPS THE WHITE MARGIN. The master has ~16px of white around the artwork.
    Left in, iOS would show white slivers wherever its superellipse mask is
    less round than the artwork's own corners.
  - MAKES THE WHITE TRANSPARENT. Even cropped, the rounded corners are white.
    They become alpha 0, which is the answer that needs no gradient model: on
    Android the background layer shows through them, and on iOS — which
    refuses an icon with an alpha channel — they are flattened onto a gradient
    at the very end, in a region iOS's own mask mostly crops anyway.
  - PADS THE ANDROID FOREGROUND TO THE SAFE ZONE. An adaptive icon is a 108dp
    layer of which only the middle 72dp survives masking, so the artwork is
    scaled to two thirds and centred. Full-bleed would put the TV's frame,
    feet and antennae outside the guaranteed area and let a circular launcher
    crop them off.
"""

import sys

from PIL import Image

MASTER = "art/app-icon.png"

IOS = "ios/TinyTube/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
ANDROID_RES = "android/app/src/main/res"

# The web app's PWA icons (web/public/manifest.webmanifest names these
# files; the names must not change). All FLATTENED like the iOS icon:
# apple-touch-icon ignores alpha anyway, and Android's install banner puts
# transparent corners on whatever the page background happens to be.
WEB_ICONS = {
    "web/public/icons/icon-192.png": 192,
    "web/public/icons/icon-512.png": 512,
    "web/public/icons/apple-touch-icon.png": 180,
}

# `--web-only` writes ONLY the web icons. The app icons are byte-committed
# outputs of a possibly different Pillow, and app development is paused —
# regenerating them would show up as an android/** change and dispatch an APK
# publish for identical artwork.
WEB_ONLY = "--web-only" in sys.argv[1:]

# Android's density buckets. The legacy icon is 48dp, the adaptive layer 108dp.
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Only the middle 72 of an adaptive icon's 108 units is guaranteed to survive
# whatever mask the launcher applies.
SAFE_FRACTION = 72 / 108


TOP = (0, 0, 0)
BOTTOM = (0, 0, 0)


def artwork() -> Image.Image:
    """The master, cropped to the art, with the white around it knocked out.

    Returns RGBA. The transparency is the point: Android's background layer
    shows through it, and iOS flattens it in main() because it has to.
    """
    im = Image.open(MASTER).convert("RGB")
    w, h = im.size
    px = im.load()

    def is_white(c):
        return c[0] > 245 and c[1] > 245 and c[2] > 245

    box = [w, h, 0, 0]
    for y in range(h):
        for x in range(w):
            if not is_white(px[x, y]):
                box[0] = min(box[0], x)
                box[1] = min(box[1], y)
                box[2] = max(box[2], x)
                box[3] = max(box[3], y)
    im = im.crop((box[0], box[1], box[2] + 1, box[3] + 1)).convert("RGBA")

    # The background gradient, from two points well inside the rounded corners,
    # extrapolated to the top and bottom edges. Vertical is a good enough model
    # of it for the only thing this is used for — painting over the corners.
    w, h = im.size
    px = im.load()
    lo = px[12, h // 4]
    hi = px[12, 3 * h // 4]
    top = tuple(int(round(l - (x - l) / 2)) for l, x in zip(lo, hi))
    bottom = tuple(int(round(x + (x - l) / 2)) for l, x in zip(lo, hi))
    global TOP, BOTTOM
    TOP, BOTTOM = top, bottom

    def band(y):
        t = y / max(h - 1, 1)
        return tuple(
            max(0, min(255, int(round(a + (b - a) * t))))
            for a, b in zip(top, bottom)
        )

    # FLOODED FROM THE BORDER, not applied to every light pixel in the image.
    #
    # The first version of this repainted anything near-white anywhere, which
    # took out the highlights in the eyes, the screen and the play button — the
    # artwork came back with blue speckles through the middle of it. Only white
    # that is CONNECTED TO THE OUTSIDE is background; white in the middle of a
    # face is a highlight and must be left alone.
    #
    # The threshold is generous on purpose so the anti-aliased rim along the
    # rounded edge goes too, and it cannot reach the artwork: the gradient's
    # own red channel is far below it, so the flood stops the moment it meets
    # the background.
    def light(c):
        return c[0] > 200 and c[1] > 200 and c[2] > 200

    seen = bytearray(w * h)
    queue = []
    for x in range(w):
        for y in (0, h - 1):
            if light(px[x, y]) and not seen[y * w + x]:
                seen[y * w + x] = 1
                queue.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if light(px[x, y]) and not seen[y * w + x]:
                seen[y * w + x] = 1
                queue.append((x, y))

    filled = 0
    while queue:
        x, y = queue.pop()
        px[x, y] = (0, 0, 0, 0)
        filled += 1
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h and not seen[ny * w + nx] and light(px[nx, ny]):
                seen[ny * w + nx] = 1
                queue.append((nx, ny))
    # And a second pass for the ANTI-ALIASED RIM.
    #
    # The flood stops at the first pixel that is not almost-white, which leaves
    # the blend between the artwork's edge and the old white margin behind — a
    # faint light outline tracing the rounded square. It is subtle at 1222px
    # and it is a halo on a home screen.
    #
    # These are caught by being much LIGHTER than the background belongs to be
    # at that row while still being blue-ish: the red channel gives it away,
    # since the gradient's red runs low and white's is 255. The TV's red frame
    # cannot be caught by this — its blue channel is nowhere near 200.
    rim = 0
    for _ in range(3):
        frontier = []
        for y in range(h):
            base = band(y)
            for x in range(w):
                if not seen[y * w + x]:
                    continue
                for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                    if not (0 <= nx < w and 0 <= ny < h) or seen[ny * w + nx]:
                        continue
                    c = px[nx, ny]
                    if c[2] > 200 and c[0] > base[0] + 30:
                        frontier.append((nx, ny, band(ny)))
        if not frontier:
            break
        for x, y, _ in frontier:
            seen[y * w + x] = 1
            px[x, y] = (0, 0, 0, 0)
            rim += 1
    print(f"knocked out {filled} background pixels and {rim} rim pixels; "
          f"interior highlights untouched")

    # Square it. The crop comes out a pixel off square, and resizing a
    # not-quite-square image into a square one stretches it by that pixel.
    side = max(w, h)
    if (w, h) != (side, side):
        square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        square.paste(im, ((side - w) // 2, (side - h) // 2))
        im = square

    return im


def main() -> None:
    art = artwork()
    print(f"artwork {art.size[0]}x{art.size[1]}, background transparent")

    # iOS: one 1024 square, FLATTENED. An app icon with an alpha channel is
    # rejected outright, so the transparent corners have to become something.
    # They become the artwork's own gradient — a vertical approximation of it,
    # which is close enough for four small wedges that iOS's superellipse mask
    # crops most of anyway.
    flat = Image.new("RGB", art.size)
    px = flat.load()
    for y in range(art.size[1]):
        t = y / max(art.size[1] - 1, 1)
        colour = tuple(
            max(0, min(255, int(round(a + (b - a) * t))))
            for a, b in zip(TOP, BOTTOM)
        )
        for x in range(art.size[0]):
            px[x, y] = colour
    flat.paste(art, (0, 0), art)

    for path, size in WEB_ICONS.items():
        flat.resize((size, size), Image.LANCZOS).save(path, "PNG")
        print(f"  {path}")
    if WEB_ONLY:
        print("--web-only: app icons left untouched")
        return

    flat.resize((1024, 1024), Image.LANCZOS).save(IOS, "PNG")
    print(f"  {IOS}")

    for bucket, scale in DENSITIES.items():
        # Legacy, for launchers older than adaptive icons: the whole artwork.
        legacy = int(48 * scale)
        out = f"{ANDROID_RES}/mipmap-{bucket}/ic_launcher.png"
        art.resize((legacy, legacy), Image.LANCZOS).save(out, "PNG")

        # Adaptive foreground: a 108dp layer with the artwork at two thirds,
        # centred, and transparent everywhere else. The background layer shows
        # through that margin, which is why it is the same gradient.
        layer = int(108 * scale)
        inner = int(round(layer * SAFE_FRACTION))
        # The margin is the SAME GRADIENT, continued, rather than transparency.
        #
        # Left transparent, the background layer showed through it and the
        # artwork's own rounded corners drew a visible square inside whatever
        # mask the launcher applied — an icon inside an icon. Continuing the
        # gradient makes the seam disappear: the artwork's edges already carry
        # these colours, so there is nothing to line up.
        # TRANSPARENT MARGIN, and transparent corners with it. The adaptive
        # icon's background layer is a gradient matching the artwork, so it
        # shows through both — which is what the two layers are for, and needs
        # no gradient model here at all.
        offset = (layer - inner) // 2
        canvas = Image.new("RGBA", (layer, layer), (0, 0, 0, 0))
        scaled = art.resize((inner, inner), Image.LANCZOS)
        canvas.paste(scaled, (offset, offset), scaled)
        canvas.save(f"{ANDROID_RES}/mipmap-{bucket}/ic_launcher_foreground.png", "PNG")
        print(f"  mipmap-{bucket}: {legacy}px legacy, {layer}px adaptive")


if __name__ == "__main__":
    main()
