# Generates the adaptive launcher icon (background, foreground, monochrome) and the animated
# launch splash from one set of measurements. Run: python3 Android/tools/generate_icon.py
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

open(f"{RES}/drawable/ic_launcher_foreground.xml", "w").write(foreground())
open(f"{RES}/drawable/ic_launcher_monochrome.xml", "w").write(monochrome())
open(f"{RES}/drawable/ic_launcher_background.xml", "w").write(background)

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
print("ok")
