# 2 — Visual language for "המחותן"

**Direction: "a printed invitation, not a bank app."** Ink navy, parchment and one restrained gold, like a well-printed Jerusalem wedding invitation or a ketubah border. Navy keeps the trust needed for money. Gold appears only on festive moments. Everything else stays quiet and highly legible.

---

## 1. Critique of the current look

1. **Modesty violation (must fix first).** `ic_suppliers` is the Material "group" icon (two human silhouettes) and is the **Suppliers tab icon in the bottom nav on every main screen**. `ic_person` (a single silhouette) is the leading icon for supplier rows (Common.kt:140, Suppliers.kt:66, Payments, Documents, Expenses, More). The brief forbids even silhouettes, so both must go.
2. **Dark-mode contrast bugs.**
   - The countdown box draws hard-coded white on `p.gold`. In dark mode that is white on #D9B26A, **1.99:1**.
   - `BtnKind.DANGER` hard-codes white on `p.danger`. In dark mode that is **2.02:1**.
   - In dark mode the hero card becomes a pale-blue (#A9C3E6) slab, the brightest thing on a dark screen.
3. **`text3` fails AA.** #8A919B on bg #F5F3EE is **2.87:1**. It is used for hints, third lines, tile sub-lines and amounts captions, which are exactly the small text that older users struggle with.
4. **Input borders are nearly invisible.** `outline` #D5D0C4 on white is **1.54:1**, below the 3:1 non-text minimum. Fields look like empty space.
5. **Tones collide.**
   - `goldSoft` #F4EBD9 and `warningSoft` #FBEFD9 are practically the same colour, so GOLD and WARNING badges can't be told apart.
   - `info` #2B5C8A is a lighter shade of `primary` #1F3A5F, so info badges look like links.
6. **The balance looks like an alarm.** "יתרה לתשלום" is painted in `warning` orange whenever it is above zero. That is its normal state. Colour should signal a problem (overdue), not a fact.
7. **There is no voice.** Heebo bold and flat navy rectangles look like any utility app. Nothing says simcha or Jerusalem, and "בס״ד" is missing.
8. **Icons are heavy and monotone.** They are filled Material glyphs beside one custom filled ring, and the eight quick actions are identical navy circles.
9. **The home screen is a wall of numbers.** Six identical stat tiles all carry the same weight.
10. **Small details.** The bottom nav has both elevation 6 and a divider (a double edge). Line spacing of 1.08 and an 11.5sp minimum are too tight for readers aged 40–65.

---

## 2. Palette

Field names follow `Palette`. **★** marks a new token.

| Token | Light | Dark | Notes |
|---|---|---|---|
| bg | `#F6F2EA` | `#0F131A` | parchment / night ink |
| surface | `#FFFDF8` | `#181D26` | warm paper, not pure white |
| surfaceAlt | `#EFE8DA` | `#212835` | segmented track, progress track |
| primary | `#1C3354` | `#AEC3E3` | ink navy |
| onPrimary | `#FFFFFF` | `#0F213A` | |
| primarySoft | `#E3E9F2` | `#243650` | tonal button, nav pill |
| gold | `#7A5A1E` | `#DFC07C` | text/icon-safe gold (GOLD tone) |
| goldSoft | `#F3E8D0` | `#352B19` | |
| ★ goldLine | `#B8964F` | `#B8964F` @70% | **decorative only**, never text |
| text | `#1A1E27` | `#EEEAE2` | |
| text2 | `#4A5260` | `#B3B8C1` | |
| text3 | `#606773` | `#9198A3` | now AA everywhere |
| divider | `#E5DCCB` | `#2A303C` | card strokes, row separators |
| outline | `#8E8574` | `#6B7381` | input/secondary-button border, ≥3:1 |
| success / Soft | `#2B6A41` / `#E1EFE4` | `#88D0A2` / `#1B3226` | |
| warning / Soft | `#A14A08` / `#FCE6D4` | `#F2AA6E` / `#3B2617` | moved toward terracotta, away from gold |
| danger / Soft | `#A6261E` / `#F8E0DC` | `#F3A39A` / `#3F201E` | |
| info / Soft | `#1D5E78` / `#DDEBF0` | `#8FCBDF` / `#17303B` | petrol, distinct from navy |
| ★ onDanger | `#FFFFFF` | `#2A0E0B` | fixes the dark danger button |
| ripple | `#1F1C3354` | `#29FFFFFF` | |
| scrim | `#99000000` | same | |
| ★ heroStart / heroEnd | `#25446E` / `#152843` | `#1D3150` / `#111C2F` | vertical gradient |
| ★ heroStroke | — | `#2F4468` | 1dp edge in dark (hero vs bg is only 1.09:1) |
| ★ heroText | `#FBF7EE` | `#FBF7EE` | |
| ★ heroText2 | `#C8D2E1` | `#C2CCDB` | |
| ★ heroGold | `#E6CB8A` | `#E3C784` | Hebrew date, ornaments on navy |
| ★ heroNumeral | `#F1DDA8` | `#F1DDA8` | countdown digits |

**No `accent2`.** A second hue would compete with danger and success and push the design toward kitsch. The hero tokens stay navy in both modes, so the hero reads as "the invitation" in light and dark alike.

**Measured contrast (WCAG):**

| Pair | Light | Dark |
|---|---|---|
| text on bg / surface | 14.9 / 16.4 | 15.5 / 14.1 |
| text2 on bg / surface / surfaceAlt | 7.1 / 7.5 / 6.5 | 9.3 / 8.5 / 7.4 |
| text3 on bg / surface / surfaceAlt | 5.1 / 5.6 / 4.7 | 6.4 / 5.8 / 5.1 |
| onPrimary on primary | 12.7 | 9.0 |
| primary on primarySoft (tonal) | 10.4 | 6.8 |
| gold on goldSoft | 5.2 | 7.9 |
| success on soft | 5.5 | 7.6 |
| warning on soft | 5.0 | 7.3 |
| danger on soft | 5.7 | 7.3 |
| info on soft | 5.9 | 7.7 |
| onDanger on danger | 7.2 | 9.0 |
| outline on surface (non-text, needs 3:1) | 3.6 | 3.5 |
| heroText on heroStart | 9.2 | 12.2 |
| heroText2 on heroStart | 6.5 | 8.1 |
| heroGold on heroStart | 6.2 | 7.9 |
| heroNumeral on heroEnd | 11.1 | 12.7 |

Every text pair passes AA, and the large majority pass AAA.

---

## 3. Typography — two families

| Family | Role | Why |
|---|---|---|
| **Heebo** (keep, variable 100–900, 122 KB) | All UI, body, labels, **all money** | Very legible at small sizes. Its **digits are tabular** (I checked: every digit has an advance of 1151 units), so amounts align in columns. It has ₪ and ״. |
| **Frank Ruhl Libre** (OFL, variable 300–900) | Display voice: wordmark, screen titles, hero, Hebrew date, countdown, section headers | Its Hebrew follows the classic Frank-Rühl letterforms of seforim and newspapers. Older Haredi readers find it familiar and dignified, not "designy". |

**Frank Ruhl Libre file size:** subset it to Hebrew + Basic Latin + ₪ + punctuation, then instance it to `wght 500:700` with fontTools. I tested this and it comes to **68 KB**.

**Rejected:** Suez One and Secular One (poster voice), Bellefair (one thin weight), David Libre (weak at display sizes), Noto Serif Hebrew (larger, neutral), Rubik (too playful).

**Never use Frank Ruhl Libre for amounts.** Its digits are proportional, so columns would wobble. It is fine for the single countdown numeral.

| Token | Font | sp | Weight | Line mult. | Usage |
|---|---|---|---|---|---|
| BRAND | FRL | 34 | 700 | 1.1 | Login/lock wordmark |
| DISPLAY | FRL | 26 | 700 | 1.15 | Hero wedding title |
| ★ COUNTDOWN | FRL | 32 | 700 | 1.0 | Medallion numeral |
| TITLE | FRL | 22 | 700 | 1.15 | Top bar title (wordmark on Home), inner screen titles |
| ★ SECTION | FRL | 18 | 600 | 1.2 | Section headers |
| ★ HEBDATE | FRL | 18 | 500 | 1.2 | Hebrew date in hero / day header in calendar |
| SUBTITLE | Heebo | 17 | 600 | 1.25 | Card titles |
| BODY / BODY_STRONG | Heebo | **16** | 400 / 600 | 1.3 | Rows, forms (up from 15) |
| CAPTION / CAPTION_STRONG | Heebo | **14** | 400 / 600 | 1.3 | Subtitles, field labels |
| LABEL | Heebo | 13 | 600 | 1.2 | Small buttons, chips, selected nav |
| SMALL | Heebo | **12** | 500 | 1.25 | Badges, nav labels. **Absolute minimum.** |
| AMOUNT_L | Heebo | 28 | 700 | 1.1 | Key balance figure |
| AMOUNT | Heebo | 20 | 700 | 1.1 | Stat tiles |
| ★ AMOUNT_ROW | Heebo | 16 | 600 | 1.2 | Trailing amounts in lists |

**Rules:**
- Always write Hebrew dates and "בס״ד" with real gershayim/geresh (U+05F4 ״, U+05F3 ׳), never ASCII quotes. Both fonts include them.
- At 1.3× text scale, the hero title may wrap to two lines and the medallion grows (see §5).

---

## 4. Shapes and surfaces

**Radii:**

| Element | Radius |
|---|---|
| Hero, login arch panel | 22 |
| Cards and tiles | 16 |
| Buttons and inputs | 12 |
| Action tiles (rounded square) | 12 at 48dp, 9 at 28dp |
| Badges | 8 (squared, so they never look tappable) |
| Chips | full pill |
| Dialogs | 24 |

**Elevation:**
- No shadows on cards: they look muddy on cream and vanish in dark mode. Cards use surface over bg plus a 1dp `divider` stroke, in both modes.
- Only floating elements get elevation: FAB 6dp, dialogs at the system default.
- Bottom nav: elevation 0 plus a 1dp top divider.

**Card anatomy:** padding 16/14 on an 8dp grid; header row = optional 28dp action tile + SUBTITLE + trailing text action; footer below a divider inset 16.

**Ledger bar:** an overdue or urgent card or row gets a 3dp start-edge bar in `danger` or `warning` (a LayerDrawable with layer gravity START; minSdk 26 supports it). Never colour the whole card.

**Stat tile:** label row = 28dp rounded-square tile (`goldSoft` fill, 16dp `gold` icon) + CAPTION `text2`; value in AMOUNT `text`; sub-line in SMALL `text2`. Colour the value only for a real state (overdue → `danger`, fully paid → `success`). The remaining balance stays `text`.

**Icon containers:**
- **Circle** = data/status tone: 40dp list leading icon on the tone's soft fill.
- **Rounded square** = action/navigation: 48dp quick actions on `primarySoft` with a `primary` icon; tile headers.

**Buttons** (minimum height 48, 52 for a screen's main CTA; 20dp icon at start, 8dp gap):

| Kind | Fill | Text / icon | Stroke |
|---|---|---|---|
| PRIMARY | primary | onPrimary | — |
| SECONDARY | surface | primary | 1dp outline |
| TONAL | primarySoft | primary | — |
| TEXT | — | primary | — |
| DANGER | danger | **onDanger** | — |
| DANGER_TEXT | — | danger | — |
| ★ HERO_OUTLINE | transparent | heroGold | 1.5dp heroGold |

- HERO_OUTLINE is used only on the hero and login.
- Disabled buttons: the whole view at 38% alpha.

**Chips:**
- 36dp tall with vertical margins to reach a 48dp touch area; full pill.
- Unselected: surface fill, 1dp `outline`, `text2` LABEL.
- Selected: `primary` fill, `onPrimary` text, plus a leading 16dp check, so selection is not shown by colour alone.

**Inputs:**
- 52dp minimum height, radius 12, `surface` fill, 1dp `outline`.
- Focus: 2dp `primary`. Error: 2dp `danger`, plus an error line with a 16dp warning icon.
- Label above in CAPTION_STRONG `text2`.
- Money fields: Heebo 600 18sp with a "₪" suffix in `text3`.

**Bottom nav:**
- Selected: filled icon variant in `primary` on a `primarySoft` pill (56×30, radius 15), label LABEL.
- Unselected: outline icon in `text2`, label SMALL.

**Dialogs:** set `android:colorAccent` and `colorBackground` in both themes so system dialogs match.

---

## 5. Decorative system

**Global rule:** ornaments appear only in the **hero, login/lock, empty states, wedding day and PDF cover**. Never on list cards, forms or section headers. Ornaments are gold only: `goldLine` on light surfaces, `heroGold` on navy.

### M1 — Diamond rule (קו יהלום)
- A 1dp horizontal line, drawn in Canvas with a LinearGradient: alpha 0 at both ends → 70% from 35% to 65% of the width → 0.
- Centre: a filled rhombus 7×8dp. Two 2.2dp dots sit 12dp either side of the centre.
- Width = parent minus 32dp.
- **Use:** inside the hero above its footer line; under the login tagline; under the empty-state text (96dp wide variant); under the PDF title.
- **Limit:** one per screen region.

### M2 — Ketubah frame (מסגרת כתובה)
- A double rule inset 8dp from the container.
  - Outer line: 1dp at 50% `heroGold`.
  - Inner line: 0.75dp at 25%, 3.5dp further in.
- **Concave corners:** each corner is a quarter-circle cut of radius 10dp, centred on the corner point. For corner (L,T) the outer path is `M L+10,T H R-10 A10,10 0 0 0 R,T+10 V B-10 A10,10 0 0 0 R-10,B H L+10 A10,10 0 0 0 L,B-10 V T+10 A10,10 0 0 0 L+10,T Z`.
- At top-centre, the lines break for 16dp and hold an 8×9dp `heroGold` diamond.
- **Use:** home hero, wedding-day state, PDF cover. Never two on one screen.

### M3 — Olive sprig (ענף זית), 24dp vector, `goldLine`

```
stem   M4.5,19.5 Q10,14.5 17,7            (stroke 1.0, round cap, no fill)
leaf   M8.07,16.14 Q8.55,12.15 4.97,10.31 Q4.49,14.31 8.07,16.14Z
leaf   M12,12.3 Q14.95,15.04 18.54,13.22 Q15.59,10.48 12,12.3Z
leaf   M16.9,7.1 Q20.59,6.28 20.76,2.5 Q17.07,3.32 16.9,7.1Z
stalk  M6.3,17.8 Q7.4,18.2 8,19             (stroke 0.7)
olive  ellipse cx 8.9 cy 19.9 rx 1.5 ry 1.15, rotated 35°
```

- **Use:** a mirrored pair flanking the 64dp empty-state icon circle, at 20dp size, 70% opacity, 8dp gap.
- On the wedding day only, a pair sits under the hero title.

### M4 — Jerusalem arch with ashlar stone (קשת ירושלמית)
- A two-centred, slightly pointed arch. For panel width W:
  - Arc radius R = 0.56W.
  - The left arc is centred at (R, s) and the right arc at (W−R, s), where s = 0.557W is the springline.
  - The arcs meet at the apex at the top of the panel. Straight jambs run down from the springline.
- Fill: hero gradient.
- Texture: ashlar courses 18dp high, blocks 48dp long, alternate courses offset 24dp. Lines are 1dp white at **6%** (4% in dark), clipped to the arch.
- Inner line: the same arch inset 10dp, 1dp `heroGold` at 50%.
- **Keystone:** a 12×16dp `heroGold` diamond at the apex, with a 2dp stroke in the bg colour, so it "cuts" the outline and sits half outside.
- **Use:** login and lock only. The launcher icon repeats the arch and keystone.

**Rejected:** pomegranate (reads as Rosh Hashanah, i.e. seasonal); chuppah as an ornament (kept as an icon only).

**Never use:** crescents (an Islamic symbol here), Magen David (a flag reading for part of the audience), hearts, doves, clinking glasses, hamsa, confetti, glitter gradients.

### Home: top bar and hero

**Top bar (Home only):**
- A 20dp line holding **"בס״ד"** at the start edge (right), FRL 500 13sp, `text2`.
- Below it, the wordmark "המחותן" in TITLE (FRL 22) `primary`, plus search and settings.
- The wedding-title subtitle is removed from the top bar, because the hero shows it.

**Hero card:**
- Full width inside 16dp gutters, radius 22, minimum height 176dp.
- Fill: vertical gradient `heroStart`→`heroEnd`; in dark mode add a 1dp `heroStroke`.
- M2 frame. Content padding 24 horizontal / 22 vertical.
- **Start column (right), top to bottom:**
  - Optional overline, FRL 500 13sp `heroGold` (e.g. "בשעה טובה ומוצלחת"; wording is the copywriter's call).
  - Title in DISPLAY `heroText`, maximum 2 lines.
  - **Hebrew date** in HEBDATE `heroGold` ("כ״ג בכסלו תשפ״ז"); the Gregorian date takes this slot if Hebrew dates are off.
  - Day, Gregorian date and venue in Heebo 14 `heroText2`.
- **End column (left): medallion.**
  - An 80dp circle filled `heroEnd`, with a 1.5dp `heroGold` ring and an inner 0.75dp ring at 35%, inset 4dp.
  - Numeral in COUNTDOWN `heroNumeral`, with "ימים" in Heebo 500 12sp `heroText2` below.
  - At 1.3× text scale it grows to 92dp.
- **Footer (optional, UX decides):** an M1 rule, then one Heebo 500 13sp `heroText2` line, e.g. "שולמו 62% מההתחייבויות".
- **States:** no date → a HERO_OUTLINE "הגדרת תאריך ומקום" replaces the medallion; wedding day → "היום" in FRL 20 plus olive sprigs; after → "מזל טוב".
- Ripple: white at 16%. Elevation 0.

### Login (and lock)
- Background: `bg`.
- The M4 arch panel has 24dp side margins, a 24dp top margin plus the status bar, and is 300dp tall (240dp under 640dp screen height or when the keyboard is open).
- Inside the arch: the launcher emblem (arch + rings) at 64dp in `heroGold`, **"המחותן"** in BRAND `heroText`, the tagline in Heebo 15 `heroText2`, and an M1 rule.
- The form card overlaps the arch bottom by 32dp: `surface`, radius 16, 1dp `divider`, 20dp padding, two 52dp fields and a 52dp PRIMARY "כניסה".
- Below the card: a 16dp lock icon plus "נתוני החתונה נשמרים רק בטלפון", CAPTION `text2`.
- **Lock screen:** the same arch at 200dp. PIN dots are 14dp (empty = 1.5dp `outline` ring, filled = `primary`). Keys are 72dp `surface` circles with a `divider` stroke and Heebo 500 28sp digits.

---

## 6. Icons

**Style rules:**
- 24dp grid, 2dp padding (a 20dp live area).
- **Outline 1.75dp stroke**, round caps and joins, 2dp corner radius on rectangles. Secondary details (depth, fringe) may drop to 1.25–1.5.
- Base set: Material Symbols **Rounded**, Outlined, weight 300–400 (Apache-2.0), matched by eye to the 1.75 custom strokes.
- Filled variants are used only for the selected bottom-nav item.
- **Colour:**
  - `text2` by default.
  - `primary` for actions.
  - The tone colour inside status circles.
  - `gold` only for brand and festive icons: rings, chuppah, the wedding-date marker.
- **Banned:** any human figure or silhouette (person, group, face, hands-as-figure); $ signs; crescents.

**New or replacement icons:**

| Icon | Replaces / use | Path or construction |
|---|---|---|
| `ic_store` | **replaces ic_suppliers and ic_person** everywhere (tab, tiles, rows, empty state) | `M3,9 L5,4 H19 L21,9` · awning scallops `M3,9 q1.5,2.4 3,0` ×6 · `M5,12 V20 H19 V12` · door `M10,20 V15.5 H14 V20` |
| `ic_chuppah` | wedding day, wedding details | `M3,8 Q12,3.2 21,8` · `M4,8 V21 M20,8 V21` · fringe `M4,8 q2,2.2 4,0` ×4 (1.5) · back poles `M8.5,12 V18 M15.5,12 V18` (1.25) |
| `ic_rings` | redraw as outline | circles r5.5 at (9,14.5) and (15,14.5) · diamond `M12,3 l2.2,2.6 -2.2,2.6 -2.2,-2.6z` filled |
| `ic_home` | outline, with an arched door | `M4,11 L12,4 L20,11` · `M6,9.5 V20 H18 V9.5` · `M10,20 V15 A2,2 0 0 1 14,15 V20` |
| `ic_key_date` | replaces ic_label in the calendar | calendar outline + filled diamond `M12,12.5 l2.3,2.75 -2.3,2.75 -2.3,-2.75z` |
| `ic_shekel` | "רישום תשלום" | 18dp circle stroke + the ₪ glyph traced from Heebo Medium |
| `ic_calendar_heb` | Hebrew-date setting | calendar outline + an "א" traced from Frank Ruhl Libre Bold, 7dp |
| `*_fill` | home, tasks, store, wallet | filled twins for the selected nav item |
| `orn_olive` | M3 | as above (an ornament, not tinted by tone) |

All the others (bell, bus, receipt, pdf, backup, lock and so on) should be re-exported from Material Symbols Rounded Outlined so the set is consistent.

---

## 7. Launcher icon — "Rings under the arch"

Two interlocked rings under a Jerusalem arch (also an abstract chuppah), crowned by a diamond keystone. It reads at 48dp and matches the login screen.

- **Background:** `<shape>` with a linear gradient, top `#24426B` → bottom `#172B48` (safer than a vector gradient).
- **Foreground** (108 viewport, everything inside the 66dp safe circle; stroke `#E2C27E`, round caps):

```
arch    M35,80 V52 A23,23 0 0 1 54,29.35 A23,23 0 0 1 73,52 V80   strokeWidth 3.4
plinth  M34.5,80 H73.5                                            strokeWidth 3.4
ring1   circle cx 48.5 cy 65 r 9                                  strokeWidth 3.2
ring2   circle cx 59.5 cy 65 r 9                                  strokeWidth 3.2
key     M54,23.5 L58.2,29.5 L54,35.5 L49.8,29.5Z  fill #F4E3B5, stroke #1F3557 2.0 (cut)
```

- **Monochrome layer:** the same paths but without the keystone's cut stroke, because a tinted single colour would merge it into the diamond.
- **Notification small icon:** the rings only, white.
- **Play Store 512px:** the same artwork on the gradient.
