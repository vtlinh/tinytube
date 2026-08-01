#!/usr/bin/env python3
"""Draws ios/TinyTube/Assets.xcassets/AppIcon.appiconset/icon-1024.png.

WHY THIS SCRIPT EXISTS. Android's launcher icon is a pair of vector drawables —
ic_launcher_background.xml and ic_launcher_foreground.xml — which are readable,
diffable, and rendered by the OS at whatever size it wants. iOS has no
equivalent: an app icon is a rasterised PNG in an asset catalogue, and a
committed 1024x1024 PNG with no source is exactly the sort of unreviewable
binary this repository avoids elsewhere (see project.yml on why the .xcodeproj
is generated rather than committed).

So the PNG is generated, and this is its source. The geometry below is the same
geometry as the Android vectors, in the same 108-unit viewport, so the two
platforms show the same face rather than two drawings that drift apart. Change
the Android vectors and re-run this.

    pip install Pillow && python3 ios/AppIcon/make-icon.py

The differences from Android, and why:

  - iOS masks the icon to a superellipse and shows the rest, where Android's
    adaptive icon guarantees only the middle 72 of 108 units. The artwork is
    therefore scaled up 1.25x here: at Android's proportions it would sit in
    the middle of a large square looking lost.
  - It is centred on the ARTWORK's centre (54, 57 — the face plus its stand),
    not the viewport's, so the thing you see is centred rather than the
    coordinate space being.
  - No transparency and no rounded corners. iOS rejects an icon with an alpha
    channel and draws its own corners; rounding them here would show a dark
    fringe inside the mask.
"""

from PIL import Image, ImageDraw

OUT = "ios/TinyTube/Assets.xcassets/AppIcon.appiconset/icon-1024.png"

SIZE = 1024
SS = 4               # supersample factor; downsampled with Lanczos for the AA

# Straight from the Android drawables.
BACKGROUND = "#2E86C8"
SCREEN = "#FFFFFF"
FACE = "#0B4A73"

# The 108-unit viewport both platforms are drawn in.
VIEWPORT = 108.0
ART_CENTRE = (54.0, 57.0)   # centre of the screen + stand, not of the viewport
SCALE_UP = 1.25             # see the note above about iOS's mask


def main() -> None:
    canvas = SIZE * SS
    k = (canvas / VIEWPORT) * SCALE_UP

    def px(x: float, y: float) -> tuple[float, float]:
        """108-space to pixels, centred on the artwork."""
        return (
            (x - ART_CENTRE[0]) * k + canvas / 2,
            (y - ART_CENTRE[1]) * k + canvas / 2,
        )

    def box(x0: float, y0: float, x1: float, y1: float) -> list[float]:
        a, b = px(x0, y0)
        c, d = px(x1, y1)
        return [a, b, c, d]

    img = Image.new("RGB", (canvas, canvas), BACKGROUND)
    d = ImageDraw.Draw(img)

    # The screen: a rounded rectangle, radius 9 in viewport units.
    d.rounded_rectangle(box(23, 34, 85, 74), radius=9 * k, fill=SCREEN)

    # Its stand.
    d.rectangle(box(46, 74, 62, 80), fill=SCREEN)

    # Eyes.
    for cx in (39.0, 69.0):
        d.ellipse(box(cx - 4.5, 48 - 4.5, cx + 4.5, 48 + 4.5), fill=FACE)

    # The smile: the same quadratic Bezier the Android vector strokes,
    # M42,59 Q54,68 66,59, stroked at 4.5 with round caps.
    p0, ctrl, p1 = (42.0, 59.0), (54.0, 68.0), (66.0, 59.0)
    width = 4.5 * k

    # STAMPED, not stroked. ImageDraw.line with joint="curve" leaves hairline
    # wedges of background between consecutive segments on a tight curve — at
    # icon scale they read as a cracked smile. Stamping a disc of the stroke's
    # own diameter along the path cannot gap, because consecutive discs
    # overlap, and it gives the round caps the Android vector asks for without
    # drawing them separately. Enough steps that the centres are well under a
    # radius apart.
    steps = 600
    r = width / 2
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u * u * p0[0] + 2 * u * t * ctrl[0] + t * t * p1[0]
        y = u * u * p0[1] + 2 * u * t * ctrl[1] + t * t * p1[1]
        cx, cy = px(x, y)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=FACE)

    img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT, "PNG")
    print(f"wrote {OUT} ({SIZE}x{SIZE})")


if __name__ == "__main__":
    main()
