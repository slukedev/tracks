# Tracks design language (iOS 27, macOS 27)

For everyone building Tracks screens. It says what Tracks looks like, which token to use for
every measure, where Liquid Glass goes and where it never goes, and the components that already
do each job. When this file and a brief disagree on a value, this file wins. The code lives in
`Apps/Shared/Design/` (`Design.swift`, `PageColor.swift`, `GlassControls.swift`, `Card.swift`,
`PlatformStyle.swift`) and `Apps/Shared/Play/Pages/AlbumZoom.swift`; the colour maths is
`TracksCore/Sources/TracksCore/Design/PageTone.swift`.

## 1. The signature

Tracks is a record of everything you play. So its colour and identity come from the music you
listened to: the covers. A page about an album, a playlist, a mix or an artist wears that cover's
colour from top to bottom, and the cover itself runs edge to edge under the bars. Everything
else stays quiet: system type, system controls, monochrome toolbars, one accent.

Concretely:

- Identity lives in the content layer: covers, the page colour taken from them, hairlines in
  that colour. Never in the controls.
- The accent (`AccentColor`) is for the selected tab, a selected row and the single primary
  action of a plain page. Nowhere else.
- Glass is the floating layer only (section 4). Content is opaque.

## 2. Tokens

Use these names. Never type a number that one of them covers. Old names (`Metrics`,
`PlayMetrics`, `PlayMotion`, `LiveUpdate`) still compile and read from `Design`; migrate when you
touch a file. `SettingsMotion` and `SettingsSpacing` (area 6) should become aliases too.

### Spacing (`Design.Spacing`)

| Token | Value |
| --- | --- |
| `xxSmall` | 2 |
| `xSmall` | 4 |
| `small` | 8 |
| `medium` | 12 |
| `large` | 16 |
| `xLarge` | 20 |
| `xxLarge` | 24 |
| `xxxLarge` | 32 |

Within a group 8 to 12, between groups 24 to 32. Separate with space and weight, not rules.

### Margins (`Design.Margin`)

| Token | iPhone | Mac | Use |
| --- | --- | --- | --- |
| `page` | 20 | 28 | The one leading edge: large title, section headers, row text, first tile of every shelf |
| `section` | 28 | 36 | Between sections of a page |
| `header` | 10 | 10 | Between a section header and its content |

Mac bottom margin: the playback capsule is a `safeAreaBar` (`pageChrome()`), so scroll views
already stop above it. Add nothing. A page that ignores the bottom safe area must add
`Design.Margin.section` of bottom content margin itself.

### Shelves and tiles (`Design.Shelf`)

| Token | iPhone | Mac |
| --- | --- | --- |
| `tile` | 160 | 176 |
| `maximumTile` (Dynamic Type cap) | 220 | 220 |
| `spacing` (between tiles) | 12 | 18 |
| `captionGap` (cover to title) | 6 | 6 |
| `lineGap` (title to subtitle) | 1 | 1 |

### Cards (`Design.Card`)

| Token | iPhone | Mac |
| --- | --- | --- |
| `radius` | 26 | 16 |
| `padding` | 16 | 16 |
| `spacing` (between cards) | 12 | 16 |
| `sectionSpacing` (between titled groups of cards) | 28 | 24 |

### Controls (`Design.Control`)

| Token | iPhone | Mac |
| --- | --- | --- |
| `glassCircle` (secondary action) | 44 | 32 |
| `playHeight` | 48 | 32 |
| `playMinimumWidth` | 150 | 92 |
| `playCircle` | 64 | 44 |
| `coverButton` (Play on a tile) | 32 | 28 |
| `minimumTarget` | 44 | 28 |
| `spacing` (between buttons in a row) | 12 | 12 |

### Radii and nesting

- Covers round by `CoverImage.radius(for:)` (4 to 12 points by size). Don't round covers yourself.
- Cards round at `Design.Card.radius`, continuous. A `Card` sets its container shape, so a
  shape inside it uses `ConcentricRectangle()` (or `.rect(corners: .concentric)`) and nests
  without a magic number.
- Without a container shape, nest with `Design.Radius.inner(of: outer, inset: padding)`:
  the outer radius minus the inset, never under 6.
- Inside glass (a sheet, a popover, a glass capsule), use `ConcentricRectangle()` for anything
  rounded, so it follows the glass's corners.
- Buttons are capsules or circles. No rounded-rectangle buttons.

### Type roles (`Font`)

| Role | Name | iPhone | Mac |
| --- | --- | --- | --- |
| Page title in content, hero name | `.pageTitle` | Large Title bold (34) | Large Title bold (26) |
| Album, playlist or mix name | `.collectionTitle` | Title 2 bold (22) | 34 bold |
| Section and shelf header | `.sectionTitle` | Title 2 bold (22) | Title 2 bold (17) |
| Row title | `.rowTitle` | Body (17) | Body (13) |
| Row subtitle, tile caption | `.rowSubtitle` | Subheadline (15), secondary | Subheadline (11), secondary |
| Eyebrow | `.eyebrow` with `.eyebrowStyle()` | Caption 2 semibold caps (11) | Caption semibold caps (10) |
| Stat figure | `.statFigure` | Title, rounded, bold, monospaced digits | same |

Every text uses a text style so it scales; things that should grow with text use
`@ScaledMetric`. Figures that change also get `.contentTransition(.numericText(value:))`.

### Colour roles

| Role | Where it comes from |
| --- | --- |
| Page colour | `pageColor(from: cover)`: a shade of the cover (section 3) |
| Type on a page colour | the page's `primary`, `secondary`, `tertiary` (set for you as the foreground style) |
| Hairlines on a page colour | `PageTone.hairlineColor` |
| Accent on a page colour | `.tint` (the cover's hue at 4.5:1), for the playing song and links |
| Accent on a plain page | `AccentColor`, for selection and the one primary action |
| Content surface | `Color.cardFill` (iPhone `secondarySystemBackground`, Mac `quaternarySystemFill`). `CardFill` draws the same |
| Page | `Color.pageBackground` (plain system background; no grey grouped page on iPhone any more) |
| Loading placeholder | `Color.placeholderFill` |

Genre, mood and party palettes are deliberate content colour; keep them.

### Motion (`Design.Motion`)

| Token | Curve | For |
| --- | --- | --- |
| `press` | snappy 0.14 | a control answering a press |
| `hover` | ease out 0.13 | the pointer arriving (Mac) |
| `value` | snappy 0.25 | a number or word changing in place |
| `panel` | smooth 0.3 | a part of a page opening or closing |
| `row` | smooth 0.28 | rows arriving, leaving, moving |
| `fade` | ease out 0.22 | opacity only; the Reduce Motion stand-in |
| `tint` | ease out 0.4 | a cover's colour settling (data, not a tap; fades only) |
| `flip` | spring 0.5, bounce 0.12 | the sleeve turning: the one flourish |
| `live` | smooth 0.5 | new listening arriving on a showing screen (iPhone only; see `LiveUpdate`) |

`Design.Motion.reduced(_:reduceMotion:)` swaps any of them for `fade`. Animate only from the
person's own action. Nothing a keyboard shortcut does animates. Nothing ticks while idle or off
screen.

### Haptics (`Design.Haptic`)

| Token | Feedback | For |
| --- | --- | --- |
| `start` | impact medium | Play and anything that starts music |
| `secondary` | impact light | Shuffle, Add, actions beside Play |
| `choice` | selection | filters, scopes, modes |
| `done` | success | something that took a while finished |

Always `.sensoryFeedback(Design.Haptic.x, trigger: localCounter)` with a counter the action
bumps. Never trigger from synced or loaded state. `PlayButton`, `GlassCircleButton` and
`CoverPlayButton` already do this.

## 3. Page colour from artwork

`PageTone` turns a cover's average colour into a page:

- Saturation is boosted a little (an average is greyer than the cover looks) and capped at 0.62,
  so a page is never neon. A cover with less than 0.08 between its channels gives a neutral page.
- Dark Mode always gives a deep page (luminance 0.01 to 0.06, white type). In light appearance a
  cover at least 0.4 luminance gives a light page (0.42 to 0.88, black type); darker covers give
  a dark page (0.01 to 0.1). Deep yellows lean to amber rather than olive.
- Primary type reaches 7:1, secondary 4.5:1, tertiary 3:1, the accent 4.5:1. Hairlines are the
  type colour at 14 percent.
- Under Increase Contrast, with no cover, or for a mostly transparent cover, the page stays the
  system's own background.

Use it:

```swift
List { ... }
    .pageColor(from: cover)          // samples the cover, handles appearance and Increase Contrast

ScrollView { ... }
    .pageColor(tone)                  // a tone you worked out yourself, nil for a plain page
```

It sets the background (under the bars too), the foreground style's three levels, `.tint`, the
colour scheme (so menus, glass, separators and the status bar match), `\.pageTone` in the
environment, and hides the scroll view's own background. It never changes the view's identity,
so a colour arriving late keeps the scroll position. A plain `List` paints each row's own
background over the page, so every row needs `.listRowBackground(Color.clear)`:
`collectionRowInsets(hasCover:)`, `LoadingRows`, `LoadMoreFailedRow` and `CollectionMessage`
set it (and `collectionRowInsets` tints the separator with the page's hairline). A row of your
own on a coloured list sets it itself.

## 4. Liquid Glass in Tracks

| Surface | Treatment | Use |
| --- | --- | --- |
| Tab bar, mini player, toolbars, sheets, popovers, menus | system glass | standard components; add nothing |
| Toolbar actions | system glass, grouped | `ToolbarItemGroup`, `ToolbarSpacer(.fixed)` between groups |
| Secondary actions beside Play | `.regular`, interactive | `GlassCircleButton`, `GlassCircleMenu`, `.glassCircle()` |
| Play on a tile | `.regular`, interactive | `CoverPlayButton` / `.coverPlayButton(...)` |
| Mac shelf arrows | system `.glass` button, circle | built into `Shelf` |
| Controls over a coloured stage (artist hero, Radio) | `.regular` for secondaries, opaque white primary | `.stagePrimary(tint:)`, `.stageSecondary` |
| Controls over rich media (Now Playing, artist photo) | `.clear` | only there |
| Morphing custom controls | `GlassEffectContainer` + `glassEffectID` | anything that appears or splits |

Never glass:

- Content: cards, rows, shelves, tiles, stats, the featured release, the playing indicator in
  a row.
- Play. The primary action is opaque (`PlayButton`).
- Glass inside glass. A control inside a glass bar is a plain glyph.
- A band behind a bar. No `toolbarBackground` colours, no material rectangles, no gradient under
  the nav bar. The scroll edge effect does it (`scrollEdgeEffectStyle(.soft, for: .top)`).
- Tint on glass by default. Glass on a page colour picks the colour up by refraction. At most
  one tinted glass element per view, and only for a reason.

Check every glass surface in light, dark, Increase Contrast, Reduce Transparency, and over a
bright and a dark cover.

## 5. Components

### Collection header (album, playlist, mix)

iPhone: the cover square and edge to edge under the bars, stretching when pulled, dissolving
into the page colour; the title (22 bold), the artist in the page's accent, the facts line in
the page's secondary (4.5:1); then Shuffle (glass) | Play (opaque) | one more glass circle; the
description under the row, left aligned, three lines with More opening the rest in a sheet. The
whole list is the page colour. Both circles take half the page colour as a glass tint
(`PageTone.glassTint`), so they read as part of the page.

The trailing circle is never a placeholder. In order: the download when downloads are on; the
page's own `secondary` (`CollectionSecondary`: Add to Library, Save as Playlist, Add Songs,
Edit Rules, a server album's download); otherwise More. At accessibility sizes the cover steps
back to a 200 point framed square under the bar, Play goes full width and the two circles sit
in a row under it, far enough apart not to merge.

Words on a coloured list name their colour (`PageInkColors`, `.pageInk()`): in a plain
`List`'s cells `.foregroundStyle(.secondary)` resolves to the system's half-strength grey, not
the page's secondary, so it measured 2.8:1 on a red page instead of 4.6:1.

```swift
List {
    Section { CollectionHeader(kind: .album, title: title, subtitle: artist, onSubtitle: openArtist,
                               facts: facts, tintCover: cover, download: .album(album),
                               secondary: CollectionSecondary("Add to Library", systemImage: "plus") { add() },
                               play: play, shuffle: shuffle) { moreItems } cover: { side in
        CoverImage(cover: cover, size: side)
    } }
    Section { ForEach(tracks) { TrackRow(...).collectionRowInsets(hasCover: false) } }
}
.listSectionSpacing(0)
.collectionPage(title: title, cover: cover)   // cover is optional; passing it colours the first frame
.toolbar {
    CollectionToolbar(share: url) { moreItems }   // Share and More in one capsule; back alone
}
```

The download lives in the header's trailing circle on iPhone (when downloads are on), so a page
shouldn't also put one in its toolbar. Mac: cover beside the words, eyebrow, 34 pt title, Play
(opaque) then Shuffle, the download or `secondary`, and More as glass circles; Play becomes a
circle where its word doesn't fit.

Light and dark: a cover under 0.4 luminance gives a dark page in both appearances (deeper in
Dark Mode), as Music keeps the art's colour; a light cover gives a light page with black type
and a black Play in light appearance.

### Shelf headers

```swift
Shelf(title: "Recently Played", items: items, open: { openPlayRoute(.recentlyPlayed) }) { item in ... }
SectionHeader("Top Songs", action: { ... })            // a title with a chevron
SectionHeader(title: "Highlights") { Button("See All") { ... } }   // a trailing link
```

A chevron after the title means the title opens the whole list. Prefer it to a "See All" link
on shelves.

### Tiles with zoom transitions

```swift
NavigationLink(value: route) {
    TileLabel(title: mix.title, subtitle: mix.subtitle) { side in MixCover(mix: mix, size: side) }
        .zoomSource(id: mix.id)
}
.buttonStyle(.pressable)
// on the destination:
MixDetailView(mix: mix).zoomDestination(id: mix.id)
```

The stack needs `zoomSpace()` (Play's stacks have it through `playDestinations()`). Both ends
must use the same id type. On the Mac these do nothing.

Play on a tile:

```swift
cover.coverPlayButton(isVisible: isHovering, isStarting: isStarting) { play() }   // Mac: on hover
cover.coverPlayButton { play() }                                                   // iPhone
```

### List rows on a page colour

Rows use the semantic styles (`.primary`, `.secondary`, `.tint`); the page colour makes them
read. Don't set white or black yourself. Separators start at the title
(`.alignmentGuide(.listRowSeparatorLeading)`, as `TrackRow` does).

### Insight panels

`Card { ... }` on the plain page (`groupedBackground()`), with a `SectionHeader` above each group.
Content panels, not glass and not iOS 15 inset cards: no grey page, no borders, generous radius,
`ConcentricRectangle()` for anything rounded inside. Under Increase Contrast a card gains an edge
all the way round.

### Floating buttons over art

Opaque Play plus glass circles in a `GlassEffectContainer`:

```swift
GlassEffectContainer(spacing: Design.Control.spacing) {
    HStack(spacing: Design.Control.spacing) {
        GlassCircleButton(title: "Info", systemImage: "info") { ... }
        PlayButton(shape: .circle) { play() }
        GlassCircleButton(title: "Favorite", systemImage: "star", isOn: isFavorite) { ... }
    }
}
```

On a coloured field that isn't a page colour, `PlayButton(tint: fieldColour)` gives white with
the field's colour for its label.

### Hero backgrounds

```swift
header
    .heroBackground { ArtworkMosaic(...) }    // runs on under the bars and the Mac sidebar
```

On the Mac, `headerUnderBar()` keeps working: the title stays in the content and the window's
own title is empty, and the scroll edge effect softens the content under the bar. It no longer
hides the bar's material by hand.

### Toolbars

- Group related actions: `ToolbarItemGroup(placement: .primaryAction)` makes one glass capsule.
  `ToolbarSpacer(.fixed)` between groups, `ToolbarSpacer(.flexible)` to push a group trailing.
- iPhone 27: put rarely used actions in `ToolbarOverflowMenu` and give items
  `.visibilityPriority(.low)` so the system folds them first, instead of a hand-made "…" menu.
- Toolbars stay monochrome over colour. Every Mac toolbar item has `.help`, and a menu bar home.

### In-page modes

`Picker` with `.segmented`, or `.tabs` on 27, placed in the toolbar (`.principal` on the Mac) or
in a `safeAreaBar(edge: .top)` so it sits on glass over the scroll edge. Never two stacked
segmented controls in the scroll content, never hand-built capsule tabs.

### Empty, loading and error states

- Loading: placeholders the shape of the real layout (`LoadingRows`, `Color.placeholderFill`),
  or nothing. Never a spinner in the middle of a page, never an empty state before the store has
  loaded.
- Empty: `CollectionMessage(text:systemImage:) { Button("The One Action") { ... } }` in the
  list's own place: a short sentence and the one action.
- Error: what happened and what to do, in one sentence, with Try Again. Never an error code.

### Mac sidebar and titles

Edge-to-edge sidebar with coloured icons; the page title in the content (`.pageTitle`), never a
title band; `heroBackground` for colour that should run under the sidebar; hover on everything
clickable within `Design.Motion.hover`.

## 6. Do not

1. Build a tab bar, a mini player overlay or a top bar by hand.
2. Paint a band, a material or a colour behind a bar (`toolbarBackground(Color...)`,
   `.ultraThinMaterial` headers).
3. Use `.ultraThinMaterial` or black veils for buttons over art: use glass.
4. Put glass on content: cards, rows, shelves, tiles, stats.
5. Make Play glass or tinted glass.
6. Put brand colour on every control.
7. Frame a header image in a box with the title below a hard edge.
8. Give a collection page a fixed colour.
9. Invent radii that don't nest.
10. Hand-build segmented controls or a "…" toolbar menu on iPhone 27.
11. Imitate an inset sidebar, grey sidebar icons, a title band or an opaque bottom transport bar
    on the Mac.
