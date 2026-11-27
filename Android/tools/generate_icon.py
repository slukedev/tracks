# Generates the adaptive launcher icon (background, foreground, monochrome), the animated
# launch splash and the Google TV banner from one set of measurements.
# Run: python3 Android/tools/generate_icon.py (the banner needs Pillow: pip install pillow)
import math, os
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")

def cookie(cx, cy, R, lobes=7, depth=0.09, steps=140):
    pts = []
    for i in range(steps):
        t = 2*math.pi*i/steps
        r = R*(1 + depth*math.cos(lobes*t))
        pts.append((cx + r*math.cos(t), cy + r*math.sin(t)))
    d = "M%.2f,%.2f" % pts[0] + "".join("L%.2f,%.2f" % p for p in pts[1:]) + "Z"
    return d

def circle(cx, cy, r):
    return "M%.2f,%.2fm-%.2f,0a%.2f,%.2f 0,1 1,%.2f 0a%.2f,%.2f 0,1 1,-%.2f 0" % (cx, cy, r, r, r, 2*r, r, r, 2*r)

def rrect(x, y, w, h, r):
    return ("M%.2f,%.2fh%.2fa%.2f,%.2f 0,0 1,%.2f,%.2fv%.2fa%.2f,%.2f 0,0 1,-%.2f,%.2fh-%.2fa%.2f,%.2f 0,0 1,-%.2f,-%.2fv-%.2fa%.2f,%.2f 0,0 1,%.2f,-%.2fz"
            % (x+r, y, w-2*r, r, r, r, r, h-2*r, r, r, r, r, w-2*r, r, r, r, r, h-2*r, r, r, r, r))

# Geometry in the 108 viewport; everything inside the 66dp safe circle.
BX, BY, BW, BH = 27, 35, 54, 38          # body
WX, WY, WW, WH = 34, 41.5, 40, 15        # window (pill)
L, Rr = (43.5, 49), (64.5, 49)           # reel centres
REEL = 5.3
HUB = 2.1
# Head guard: a soft trapezoid at the bottom.
guard = "M44,63.5h20a2,2 0,0 1,1.9 1.4l1.6,5.1h-27l1.6,-5.1a2,2 0,0 1,1.9 -1.4z"

def reel_group(name, c, color_reel, color_hub, color_hole, animated):
    cx, cy = c
    inner = (f'<path android:fillColor="{color_reel}" android:pathData="{cookie(cx, cy, REEL)}" />'
             f'<path android:fillColor="{color_hub}" android:pathData="{cookie(cx, cy, HUB+0.6, lobes=6, depth=0.12)}" />'
             f'<path android:fillColor="{color_hole}" android:pathData="{circle(cx, cy, 1.1)}" />')
    return f'<group android:name="{name}" android:pivotX="{cx}" android:pivotY="{cy}">{inner}</group>'

def foreground(animated=False):
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <!-- A soft shadow under the shell, for depth on the launcher. -->
    <path android:fillColor="#33000000" android:pathData="{rrect(BX+0.5, BY+2.2, BW-1, BH, 9)}" />
    <path android:pathData="{rrect(BX, BY, BW, BH, 9)}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="27" android:startY="35" android:endX="81" android:endY="73">
                <item android:offset="0" android:color="#FFFFFBFA" />
                <item android:offset="1" android:color="#FFEFDDE6" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:fillColor="#FF2B2235" android:pathData="{rrect(WX, WY, WW, WH, 7.5)}" />
    {reel_group("reelLeft", L, "#FFFFB3C1", "#FFFFFBFA", "#FF2B2235", animated)}
    {reel_group("reelRight", Rr, "#FFFFB3C1", "#FFFFFBFA", "#FF2B2235", animated)}
    <path android:fillColor="#FFE2CCD9" android:pathData="{guard}" />
    <path android:fillColor="#FFB79DB0" android:pathData="{rrect(48.5, 66.2, 11, 1.6, 0.8)}" />
</vector>
'''

def monochrome():
    # One color; the window and reels are cut out so the themed icon still reads as a cassette.
    return f'''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF000000" android:fillType="evenOdd"
        android:pathData="{rrect(BX, BY, BW, BH, 9)} {rrect(WX, WY, WW, WH, 7.5)}" />
    <path android:fillColor="#FF000000" android:fillType="evenOdd"
        android:pathData="{cookie(L[0], L[1], REEL-0.8)} {circle(L[0], L[1], HUB)}" />
    <path android:fillColor="#FF000000" android:fillType="evenOdd"
        android:pathData="{cookie(Rr[0], Rr[1], REEL-0.8)} {circle(Rr[0], Rr[1], HUB)}" />
</vector>
'''

background = '''<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="40" android:centerY="30" android:gradientRadius="95">
                <item android:offset="0" android:color="#FFFF7A93" />
                <item android:offset="0.55" android:color="#FFE8355C" />
                <item android:offset="1" android:color="#FFB3123C" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
'''

# The layers carry a "tracks" prefix: Apple's MusicKitAuth AAR ships Android Studio's template
# ic_launcher_background/ic_launcher_foreground, and its drawable-anydpi-v24 foreground outranks
# an app drawable/ of the same name, which put the Android robot on the launcher icon.
open(f"{RES}/drawable/ic_tracks_launcher_foreground.xml", "w").write(foreground())
open(f"{RES}/drawable/ic_tracks_launcher_monochrome.xml", "w").write(monochrome())
open(f"{RES}/drawable/ic_tracks_launcher_background.xml", "w").write(background)

# The launch splash: the same cassette, its reels turning while the app opens.
splash = f'''<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
{foreground(True)}
    </aapt:attr>
    <target android:name="reelLeft">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="rotation" android:valueFrom="0" android:valueTo="720"
                android:duration="1000" android:interpolator="@android:interpolator/fast_out_slow_in" />
        </aapt:attr>
    </target>
    <target android:name="reelRight">
        <aapt:attr name="android:animation">
            <objectAnimator android:propertyName="rotation" android:valueFrom="0" android:valueTo="720"
                android:duration="1000" android:interpolator="@android:interpolator/fast_out_slow_in" />
        </aapt:attr>
    </target>
</animated-vector>
'''
open(f"{RES}/drawable/splash_cassette.xml", "w").write(splash.replace('<?xml version="1.0" encoding="utf-8"?>', ''))

def banner():
    # Google TV's launcher banner: 320x180 at xhdpi, the icon's background and cassette with the
    # app's name beside it. Drawn large, then scaled down for each density.
    from PIL import Image, ImageDraw, ImageFont
    W, H, SS = 640, 360, 4
    w, h = W*SS, H*SS
    # The icon background's radial gradient, stretched to the banner.
    stops = [(0, (0xFF, 0x7A, 0x93)), (0.55, (0xE8, 0x35, 0x5C)), (1, (0xB3, 0x12, 0x3C))]
    def shade(t):
        t = min(max(t, 0), 1)
        for (a, ca), (b, cb) in zip(stops, stops[1:]):
            if t <= b:
                f = (t - a) / (b - a)
                return tuple(round(x + (y - x)*f) for x, y in zip(ca, cb))
        return stops[-1][1]
    small = Image.new("RGB", (W//4, H//4))
    cx, cy, radius = W//4*0.37, H//4*0.28, W//4*0.88
    small.putdata([shade(math.hypot(x - cx, y - cy)/radius) for y in range(H//4) for x in range(W//4)])
    img = small.resize((w, h), Image.BICUBIC).convert("RGBA")
    d = ImageDraw.Draw(img)

    # The cassette in the 108 viewport, scaled so its body is about half the banner's height.
    k = h*0.5/BH
    ox, oy = w*0.09 - BX*k, (h - BH*k)/2 - BY*k
    def P(x, y): return (ox + x*k, oy + y*k)
    def box(x, y, bw, bh): return [P(x, y), P(x + bw, y + bh)]
    shadow = Image.new("RGBA", (w, h))
    ImageDraw.Draw(shadow).rounded_rectangle(box(BX+0.5, BY+2.2, BW-1, BH), radius=9*k, fill=(0, 0, 0, 0x33))
    img.alpha_composite(shadow)
    d.rounded_rectangle(box(BX, BY, BW, BH), radius=9*k, fill=(0xFB, 0xF1, 0xF2))
    d.rounded_rectangle(box(WX, WY, WW, WH), radius=7.5*k, fill=(0x2B, 0x22, 0x35))
    def poly(c, R, lobes, depth, color):
        pts = [P(c[0] + R*(1 + depth*math.cos(lobes*t))*math.cos(t), c[1] + R*(1 + depth*math.cos(lobes*t))*math.sin(t))
               for t in (2*math.pi*i/140 for i in range(140))]
        d.polygon(pts, fill=color)
    for c in (L, Rr):
        poly(c, REEL, 7, 0.09, (0xFF, 0xB3, 0xC1))
        poly(c, HUB+0.6, 6, 0.12, (0xFF, 0xFB, 0xFA))
        d.ellipse(box(c[0]-1.1, c[1]-1.1, 2.2, 2.2), fill=(0x2B, 0x22, 0x35))
    d.polygon([P(44, 63.5), P(64, 63.5), P(67.5, 70), P(40.5, 70)], fill=(0xE2, 0xCC, 0xD9))
    d.rounded_rectangle(box(48.5, 66.2, 11, 1.6), radius=0.8*k, fill=(0xB7, 0x9D, 0xB0))

    font = ImageFont.truetype(os.path.join(RES, "font", "google_sans_flex.ttf"), round(h*0.25))
    font.set_variation_by_axes([96, 100, 640, 100, 0])
    tx = P(BX + BW, 0)[0] + w*0.06
    d.text((tx, h/2), "Tracks", font=font, fill=(0xFF, 0xFF, 0xFF), anchor="lm")

    for folder, size in (("drawable-xhdpi", (320, 180)), ("drawable-xxxhdpi", (640, 360))):
        os.makedirs(f"{RES}/{folder}", exist_ok=True)
        img.resize(size, Image.LANCZOS).convert("RGB").save(f"{RES}/{folder}/tv_banner.png", optimize=True)

banner()
print("ok")
