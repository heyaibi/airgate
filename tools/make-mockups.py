#!/usr/bin/env python3
"""Compose Airgate screenshots into Android phone mockups.

Pieces designed in SVG (art/screens/mockups/pieces/*.svg) are rendered to PNG
once, then stretched to fit each screenshot's aspect ratio:
  - top.png: opaque bezel (rounded top corners) + transparent camera zone with
    the punch-hole camera; width tracks the screen width.
  - bottom.png: opaque chin (rounded bottom corners); width tracks the screen.
  - left/right.png: flat side strips; cropped to the screen height.
A drop shadow and a hairline screen border are added at merge time. The screen
content is always pasted at its native resolution, so only the bezel scales —
any screenshot aspect ratio is supported by stretching the side strips.

Run after `make screens` / `make screens-dark` (or `make mockups`).
"""
import os
import shutil
import subprocess
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, "..", "art", "screens"))
MOCK = os.path.join(ROOT, "mockups")
PIECES = os.path.join(MOCK, "pieces")

SIDE = 24
PROT = 10              # side-button protrusion past the body edge
TOP_BEZEL = 110        # opaque top bezel
CAMERA_ZONE = 0        # no in-frame camera: the screenshot's status bar already renders one
BOTTOM_BEZEL = 110
TOP_STRIP_H = TOP_BEZEL + CAMERA_ZONE
RADIUS = 56            # body corner radius
PAD = 40               # shadow margin around the body
PIECE_W = 1116         # design width of top/bottom strips (must equal body_w so no horizontal crop)
CROP = 6              # pixels cropped off each edge of the screenshot (removes the emulator's black corners)
SCREEN_RADIUS = 32     # transparent rounded-corner radius on the cropped screenshot
BODY_COLOR = (28, 28, 28, 255)  # #1c1c1c, matches the SVG piece fill
TOP_STRIP_TALL = 76    # top bezel strip height (visible 70 + 6 hidden behind the screenshot)
BOTTOM_STRIP_TALL = 76
SCREEN_TOP = 70        # screenshot top edge, measured from the phone's top edge; the strip's
                       # bottom 6px (76-70) sits behind the screenshot and shows through its corner cutouts


def render_pieces():
    for name in ("top", "bottom", "left", "right"):
        svg = os.path.join(PIECES, name + ".svg")
        png = os.path.join(PIECES, name + ".png")
        subprocess.run(
            ["rsvg-convert", "-o", png, svg], check=True, capture_output=True
        )
        print(f"piece {name}.png")


def crop_or_resize(img, width, height):
    """Fit img to exactly (width, height): crop if larger, resize if smaller."""
    w, h = img.size
    if w == width and h == height:
        return img
    if w >= width and h >= height:
        return img.crop((0, 0, width, height))
    return img.resize((width, height), Image.LANCZOS)


def round_corners(im, radius, fill):
    """Round the screenshot's corners with NO black background.

    A rounded-corner mask makes the cutouts transparent, and the body-colour
    plate behind fills them — so the rounded screen reveals the blue chassis
    (matching the SVG pieces) instead of black triangles or the page background.
    """
    im = im.convert("RGBA")
    w, h = im.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), radius=radius, fill=255)
    plate = Image.new("RGBA", (w, h), fill)
    plate.paste(im, (0, 0), mask)
    return plate


def make_mockup(screenshot_path, out_path):
    screen = Image.open(screenshot_path).convert("RGBA")
    if CROP:
        screen = screen.crop((CROP, CROP, screen.width - CROP, screen.height - CROP))
    sw, sh = screen.size

    # Transparent rounded corners on the screenshot. The cutouts are not filled:
    # the top/bottom strips overlap the screen by STRIP_OVERLAP px and cover the
    # corner cutouts, so the blue bezel shows through with no black triangles.
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh - 1), radius=SCREEN_RADIUS, fill=255)
    screen.putalpha(mask)

    body_w = sw + 2 * SIDE
    body_h = SCREEN_TOP + sh + SCREEN_TOP   # visible bezel above/below equals SCREEN_TOP

    top = crop_or_resize(Image.open(os.path.join(PIECES, "top.png")).convert("RGBA"), body_w, TOP_STRIP_TALL)
    bottom = crop_or_resize(Image.open(os.path.join(PIECES, "bottom.png")).convert("RGBA"), body_w, BOTTOM_STRIP_TALL)
    left = crop_or_resize(Image.open(os.path.join(PIECES, "left.png")).convert("RGBA"), SIDE + PROT, sh)
    right = crop_or_resize(Image.open(os.path.join(PIECES, "right.png")).convert("RGBA"), SIDE + PROT, sh)

    W, H = body_w + 2 * PAD, body_h + 2 * PAD

    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))

    # Drop shadow from a solid body silhouette (offset down, blurred). The
    # silhouette stops at the body edge: the side buttons protrude PAST it, so
    # they read as physical buttons sticking out of the phone rather than bands
    # drawn on the bezel.
    radius = round(RADIUS * body_w / PIECE_W)
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rounded_rectangle(
        (PAD, PAD + 12, PAD + body_w, PAD + 12 + body_h), radius=radius, fill=(0, 0, 0, 160)
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    canvas.alpha_composite(shadow)

    # Bezel strips and side rails go behind the screenshot; a solid blue chassis
    # covers the whole screen area so every transparent corner cutout reveals
    # uniform body colour (no light-blue blend, no black). The screenshot is
    # pasted last so it sits IN FRONT.
    canvas.alpha_composite(top, (PAD, PAD))
    canvas.alpha_composite(bottom, (PAD, PAD + SCREEN_TOP + sh - (BOTTOM_STRIP_TALL - SCREEN_TOP)))
    canvas.alpha_composite(left, (PAD - PROT, PAD + SCREEN_TOP))
    canvas.alpha_composite(right, (PAD + body_w - SIDE, PAD + SCREEN_TOP))
    canvas.alpha_composite(Image.new("RGBA", (sw, sh), BODY_COLOR), (PAD + SIDE, PAD + SCREEN_TOP))
    canvas.alpha_composite(screen, (PAD + SIDE, PAD + SCREEN_TOP))

    canvas.save(out_path, "PNG")


def main():
    if shutil.which("rsvg-convert") is None:
        raise SystemExit("rsvg-convert not found (install librsvg)")
    if not os.path.isdir(PIECES):
        raise SystemExit(f"{PIECES} missing")

    render_pieces()

    pngs = []
    for sub in ("", "guide"):
        d = os.path.join(ROOT, sub) if sub else ROOT
        for fn in sorted(os.listdir(d)):
            if fn.endswith(".png"):
                pngs.append((sub, fn))

    for sub, fn in pngs:
        src = os.path.join(ROOT, sub, fn) if sub else os.path.join(ROOT, fn)
        make_mockup(src, os.path.join(MOCK, fn))
        print(f"mockup {fn}")
    print(f"done: {len(pngs)} mockups -> {MOCK}")


if __name__ == "__main__":
    main()
