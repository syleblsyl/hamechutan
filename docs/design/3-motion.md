# 3 · Motion, micro-interactions and haptics — המחותן

**Principle:** motion confirms, it does not perform. Every animation answers *where did I go*, *did it work* or *what changed*. It is calm (overshoot ≤3%, nothing loops except a busy indicator), short (routine feedback ends within 1.2 s), and the app is complete without it.

## 0. What the current code dictates

- `render()` rebuilds the whole screen on every push, pop and `refresh()`. So there are two patterns:
  - **Animate-then-refresh** for in-place actions (task check): write data, animate the row, then refresh. The rebuild matches what is on screen, so nothing flashes.
  - **Animate-on-next-build** for changes after navigation (payment saved, pop): a process-level memory of displayed values lets the rebuilt screen tick old → new.
- The FAB lives in `content` and must be handled apart from the page.
- Home is built *under* the PIN overlay in `onCreate`, so entrance effects wait for unlock.
- The receipt and linked-tasks dialogs open *before* `close()` and would cover any celebration (fix in §4.4).

---

## 1. Motion system (`ui/Motion.kt`, new, ~80 lines)

| Token | ms | Use |
|---|---|---|
| `XS` | 100 | press-down, colour and state toggles, tab pill |
| `S` | 200 | exits, fades, small entrances, tab switch, error text |
| `M` | 300 | screen push, card entrance, row collapse |
| `L` | 500 | value ticks, progress changes, digit roll |
| *showcase* | 900 (max 2400) | ring first sweep and the six delight moments only |

| Curve | cubic-bezier | Use |
|---|---|---|
| `STANDARD` | (0.2, 0, 0, 1) | anything moving or changing on screen |
| `ENTER` | (0.05, 0.7, 0.1, 1) | elements arriving (decelerate) |
| `EXIT` | (0.3, 0, 0.8, 0.15) | elements leaving (accelerate) |
| `SETTLE` | (0.34, 1.3, 0.64, 1) | press release, PIN dot, stamp. Peaks at about 3% overshoot. |

**Rules**
1. Entrances use `ENTER`. Exits use `EXIT` at about 0.7× the entrance duration. On-screen changes use `STANDARD`.
2. Distances are 8–32 dp. Nothing slides full-screen, and nothing travels more than 32 dp except particles (72 dp at most).
3. Animate only alpha, translation and scale (GPU properties). Layout params are animated in exactly one case: collapsing a single row.
4. **Data first.** The DB write happens before any animation starts. An animation never gates correctness.
5. **Interruptible.** New navigation calls `end()` (not `cancel()`) on running choreography, so the end state always applies.
6. One choreography at a time per screen. One haptic per user action.

**Reduced motion:** `Motion.on = ValueAnimator.areAnimatorsEnabled() && !prefs.motionOff`. The first check (API 26) covers "remove animations" and duration scale 0; the second is a suggested "אנימציות" switch in Settings for slow phones. When off:
- Navigation swaps instantly (as today). Ticks, rings and bars show final values.
- No shake (error text and haptic still carry the error). No sparkle views are created; stamps and "מזל טוב" headers appear statically.
- Task completion refreshes at once and still shows the undo bar.
- Sequences chain through `onAnimationEnd` (fires synchronously when animators are off), never `postDelayed`.
- Haptics still apply (§6).

```kotlin
object Motion {
    const val XS = 100L; const val S = 200L; const val M = 300L; const val L = 500L
    val STANDARD = PathInterpolator(0.2f, 0f, 0f, 1f)
    val ENTER    = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val EXIT     = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
    val SETTLE   = PathInterpolator(0.34f, 1.3f, 0.64f, 1f)
    var userOff = false
    val on get() = !userOff && ValueAnimator.areAnimatorsEnabled()
    private val onceKeys = HashSet<String>()                  // process lifetime
    fun once(key: String) = onceKeys.add(key)
    val shown = HashMap<String, Long>()                       // last displayed values, for ticks
    /** Shared press feedback: no touch listeners, works inside ScrollView (pressed state is delayed on scroll). */
    fun press(scale: Float) = StateListAnimator().apply {
        addState(intArrayOf(android.R.attr.state_pressed), scaleTo(scale, XS, STANDARD))
        addState(intArrayOf(), scaleTo(1f, 160, SETTLE))
    }
    private fun scaleTo(s: Float, d: Long, i: TimeInterpolator) = AnimatorSet().apply {
        playTogether(ObjectAnimator.ofFloat(null as View?, View.SCALE_X, s), ObjectAnimator.ofFloat(null as View?, View.SCALE_Y, s))
        duration = d; interpolator = i
    }
}
```

---

## 2. Screen transitions (single Activity, `content` FrameLayout) · M · medium risk

| Navigation | Outgoing page | Incoming page | Total |
|---|---|---|---|
| `push` | translationX 0 → +32 dp (moves right). Alpha 1 → 0 in the first 33%. | translationX −32 dp → 0 (enters from the left, the RTL "forward" side). Alpha 0 → 1 from 33% to 100%. | 300 ms |
| `pop`, `popUntil` | 0 → −32 dp, fades out first | +32 dp → 0 | 250 ms |
| `openTab` | alpha → 0 in 70 ms | alpha 0 → 1, scale 0.985 → 1 (fade-through: tabs are peers, so there is no direction) | 200 ms |
| `replaceTop` (form saved → detail) | crossfade with no movement: "same place, new state" | | 200 ms |
| `refresh` | none. Instant, because speed matters more than flourish. Chip filters are instant too. | | 0 |

The page strip moves right on push and left on back, matching the RTL back arrow (→).

- **Top bar:** new title fades in (200 ms) and slides 8 dp with the page.
- **Bottom nav:** shows/hides *instantly* at t=0; animating to `GONE` would relayout content at the end.
- **Tab pill:** scaleX 0.6 → 1 plus alpha, 200 ms `ENTER`. **FAB:** old one removed at t=0; new one scales 0.8 → 1 with alpha, 200 ms `ENTER`, 100 ms delay.
- **Touch** is swallowed while a transition runs (≤300 ms), which also fixes double-tap pushing two screens.
- Both pages use `LAYER_TYPE_HARDWARE` during the transition. `keepView` cached views get alpha, translation and scale reset before reuse.
- **Lock and subscription overlays appear instantly** (content must never show through); only dismissal animates (§4.7).
- **Theme restart:** `overridePendingTransition(fade_in, fade_out)` instead of `(0, 0)`.

```kotlin
enum class Nav { NONE, PUSH, POP, TAB, REPLACE }
private var running: Animator? = null

private fun render(nav: Nav = Nav.NONE) {
    running?.end()                                             // finish previous transition (end-state applies)
    val s = stack.lastOrNull() ?: return
    buildTopBar(s)
    fabView?.let { content.removeView(it) }; fabView = null
    val old = content.getChildAt(0)
    val v = buildScreenView(s)                                 // existing try/catch build
    (v.parent as? ViewGroup)?.removeView(v)
    v.alpha = 1f; v.translationX = 0f; v.scaleX = 1f; v.scaleY = 1f
    content.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    restoreScroll(s, v); bottomNav.visibility = if (stack.size == 1) View.VISIBLE else View.GONE
    if (old == null || old === v || nav == Nav.NONE || !Motion.on) { old?.let { if (it !== v) content.removeView(it) }; addFab(s, false); return }

    val rtl = if (content.layoutDirection == View.LAYOUT_DIRECTION_RTL) 1f else -1f
    val sign = when (nav) { Nav.PUSH -> rtl; Nav.POP -> -rtl; else -> 0f }
    val (dur, split) = when (nav) { Nav.PUSH -> 300L to .33f; Nav.POP -> 250L to .33f; else -> 200L to .35f }
    val d = ui.dpf(32)
    old.setLayerType(View.LAYER_TYPE_HARDWARE, null); v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    v.alpha = 0f
    running = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = dur; interpolator = LinearInterpolator()
        addUpdateListener {
            val t = it.animatedFraction; val m = Motion.STANDARD.getInterpolation(t)
            old.translationX = sign * d * m
            v.translationX = sign * d * (m - 1f)
            old.alpha = 1f - Motion.EXIT.getInterpolation((t / split).coerceAtMost(1f))
            v.alpha = Motion.ENTER.getInterpolation(((t - split) / (1 - split)).coerceIn(0f, 1f))
            if (nav == Nav.TAB) { val k = .985f + .015f * v.alpha; v.scaleX = k; v.scaleY = k }
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                content.removeView(old)
                old.alpha = 1f; old.translationX = 0f; old.setLayerType(View.LAYER_TYPE_NONE, null)
                v.setLayerType(View.LAYER_TYPE_NONE, null); running = null
                addFab(s, true)
            }
        })
        start()
    }
}
override fun dispatchTouchEvent(ev: MotionEvent) = running != null || super.dispatchTouchEvent(ev)
```

**Risk:** heavy screens (60 task rows) build synchronously, so the first frame can be late; the animator still starts from 0 on the next frame. Hiding the keyboard on pop resizes the window mid-transition; acceptable.

---

## 3. Home "gadgets"

### 3.1 Countdown digit roll · M · low risk
- **When:** only if the day count *changed* since last seen (`prefs.countdownLastDays`), e.g. 43 → 42 on the first open of a new day. Never counts up from 0 (that reads as the wedding moving away).
- **Motion:** odometer, units digit first, 40 ms stagger, 500 ms per digit `ENTER`; decreasing digits drop in from above; starts 250 ms after Home's entrance. "ימים" stays static.
- Uses `tnum` and `ui.scale`; `contentDescription` is the final value.

```kotlin
class RollingNumber(ctx: Context, private val p: Paint) : View(ctx) {
    private var a = ""; private var b = ""; private var ms = Float.MAX_VALUE
    private val dw get() = p.measureText("0"); private val fm = p.fontMetrics
    fun show(value: Long, previous: Long?) {
        val n = maxOf("$value".length, "${previous ?: value}".length)
        b = "$value".padStart(n); a = "${previous ?: value}".padStart(n)
        contentDescription = "$value"; requestLayout()
        if (previous == null || previous == value || !Motion.on) { ms = Float.MAX_VALUE; invalidate(); return }
        ValueAnimator.ofFloat(0f, Motion.L + 40f * (n - 1)).apply {
            duration = (Motion.L + 40 * (n - 1)); startDelay = 250; interpolator = LinearInterpolator()
            addUpdateListener { ms = it.animatedValue as Float; invalidate() }
        }.start(); ms = 0f
    }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((dw * b.length).toInt(), (fm.descent - fm.ascent).toInt())
    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val base = -fm.ascent
        val down = if ((b.trim().toLongOrNull() ?: 0) < (a.trim().toLongOrNull() ?: 0)) 1f else -1f
        c.clipRect(0, 0, width, height)
        for (i in b.indices) {
            val x = i * dw
            val lt = Motion.ENTER.getInterpolation(((ms - 40f * (b.length - 1 - i)) / Motion.L).coerceIn(0f, 1f))
            if (a[i] == b[i] || lt >= 1f) { c.drawText(b, i, i + 1, x, base, p); continue }
            p.alpha = (255 * (1 - lt)).toInt(); c.drawText(a, i, i + 1, x, base + down * h * lt, p)
            p.alpha = (255 * lt).toInt();       c.drawText(b, i, i + 1, x, base - down * h * (1 - lt), p)
            p.alpha = 255
        }
    }
}
```

### 3.2 Budget ring · M · low risk
If the visual team adopts a ring (otherwise the same timing goes on `Ui.Bar`):
- Arcs start at 12 o'clock, clockwise (dials read clockwise in Hebrew too; *open question for UX*).
- **First unlocked Home per session:** committed arc (`primarySoft`) sweeps 0–700 ms, paid arc (`success`) 150–900 ms, both `ENTER`; the centre % counts up in sync. Percentages may count up; shekels never do.
- **Later changes:** old → new over 500 ms. **Over budget:** arc stays full, track crossfades to `dangerSoft` (300 ms), no flashing.

```kotlin
class RingView(ctx: Context, ui: Ui) : View(ctx) {
    private var c = 0f; private var pd = 0f; private val sw = ui.dpf(10); private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; color = ui.p.surfaceAlt }
    private val light = Paint(track).apply { color = ui.p.primarySoft; strokeCap = Paint.Cap.ROUND }
    private val strong = Paint(light).apply { color = ui.p.success }
    var onFrame: ((Float) -> Unit)? = null                     // drives the centre % label
    fun animateTo(committed: Float, paid: Float, firstSweep: Boolean) {
        val c0 = if (firstSweep) 0f else c; val p0 = if (firstSweep) 0f else pd
        if (!Motion.on) { c = committed; pd = paid; onFrame?.invoke(1f); invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (firstSweep) 900 else Motion.L; interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction
                c  = c0 + (committed - c0) * Motion.ENTER.getInterpolation((t / .78f).coerceAtMost(1f))
                val tp = Motion.ENTER.getInterpolation(((t - .17f) / .83f).coerceIn(0f, 1f))
                pd = p0 + (paid - p0) * tp; onFrame?.invoke(tp); invalidate()
            }
        }.start()
    }
    override fun onDraw(cv: Canvas) {
        val i = sw / 2 + 1; oval.set(i, i, width - i, height - i)
        cv.drawArc(oval, 0f, 360f, false, track)
        if (c > 0) cv.drawArc(oval, -90f, 360f * c.coerceAtMost(1f), false, light)
        if (pd > 0) cv.drawArc(oval, -90f, 360f * pd.coerceAtMost(1f), false, strong)
    }
}
```

### 3.3 Money ticks · S · low risk
- **Never from zero:** users aged 40–65 read the number mid-animation ("why 3,200?"), and amounts are the app's truth.
- **Only deltas:** an amount view registers a key (`"home.paid"`, `"exp:12:remaining"`); if `Motion.shown[key]` differs, it ticks old → new over 500 ms `STANDARD` in whole shekels, ending on exact `Money.format`. Width is already `MATCH` and `tnum` prevents jitter, so no relayout.
- One mechanism covers payments, price edits and cancellations, with no plumbing.

### 3.4 Staggered entrance · S · low risk
First *unlocked* Home build per process only (`!act.isLocked && Motion.once("home")`), never on refresh. The first 7 children of the page column: alpha 0 → 1, translationY 12 dp → 0, 300 ms `ENTER`, 40 ms stagger (done within 540 ms).

### 3.5 Gold shimmer · S · low risk
Countdown box only. A 20° band, 40% of the box width, white at 22% peak alpha (14% dark), sweeps right → left over 900 ms `STANDARD`, **once**, 400 ms after entrance or after a digit roll. Never loops (that reads as "loading"). A `ShimmerDrawable` as `foreground` (API 23+) with a translated `LinearGradient`.

---

## 4. Micro-interactions

### 4.1 Task completion (the most frequent action) · M · medium risk
`taskRow` gets a custom `CheckView` (Canvas) and a `StrikeText` (a TextView whose `onDraw` draws a line by `progress`).

| t (ms) | What happens |
|---|---|
| 0 | Haptic. `repo.setTaskStatus` runs at once. The row stops responding to taps. |
| 0–150 | Circle fills with success colour, scale 0.85 → 1, `SETTLE` |
| 100–300 | Check path draws (`PathMeasure.getSegment`), `ENTER` |
| 120–340 | Strike line draws from the line's right edge leftward (RTL). Title colour text → text3. |
| 340–640 | Hold, so the user registers "done" |
| 640–940 | Row collapses: height → 0 plus alpha (single-row layout animation). The divider above goes with it. |
| 940 | `onChanged()`, which refreshes. It is debounced, so several quick checks produce one refresh. |

- If the row stays in the list (filter "הכל"), skip the collapse and refresh at 450 ms. Reopening un-draws the check (150 ms).
- **Undo bar replaces the toast:** "המשימה סומנה כבוצעה · ביטול", slides up 16 dp with fade (200 ms `ENTER`), stays 4 s (6 s with TalkBack, plus `announceForAccessibility`), exits in 150 ms. Important for mis-taps.
- An `onStart` refresh mid-animation is harmless: data is already written.

### 4.2 Urgency change · S
- The screen sets `pendingHighlight = taskId` before refreshing (rows tagged `"task:$id"`). After the rebuild the row smooth-scrolls into view, its background fades from the tone's soft colour (`dangerSoft` for "דחוף") to transparent (300 ms hold, 900 ms fade), and the badge pops 0.85 → 1 (250 ms `SETTLE`). Low risk.
- In the detail screen the segmented pill *slides* to the new segment (200 ms `STANDARD`); refresh waits for it.

### 4.3 Expand and collapse inside forms · S
`LayoutTransition` (CHANGING, 200 ms) on the specific *cards* where rows toggle (other payment method, "לשמור כאמצעי תשלום קבוע", welcome-card dismissal). Never on page columns. Low risk.

### 4.4 Payment saved → fully paid · M · medium risk
- **Routine:** haptic at save, pop (250 ms), then "שולם עד כה" and "יתרה" tick (§3.3) and the progress bar moves old → new (500 ms). Home tiles and ring do the same when paid from Home.
- **Remaining >0 → ≤0:** when the tick lands on ₪0, the **"שולם במלואו" stamp** lands (D4). From Home, the bottom bar shows a small stamp icon with the burst.
- **Sequencing fix (with UX and copy):** show the linked-tasks dialog ("החשבון עם {ספק} נסגר") **600 ms after the stamp**, and make the receipt prompt an inline "צרפו קבלה" row instead of a dialog.

### 4.5 Press feedback · S · low risk
`stateListAnimator = Motion.press(s)`, ripple kept. Buttons 0.97, cards and tiles 0.985, quick-action bubbles 0.92, FAB and PIN keys 0.94. Full-width list rows: ripple only. The FAB never collapses on scroll; its label matters to this audience.

### 4.6 Error shake · S · low risk
Smooth-scroll the field into view, then shake the **input box**: translationX keyframes 0, −6, 6, −4, 4, −2, 0 dp over 360 ms. Error text fades in and drops 4 dp (200 ms). Only the first invalid field shakes. Wired into `TextInput.error()` and `Picker.error()`, so every form gets it.

### 4.7 PIN screen · M · low risk
- Update only the changed dot (no full `renderDots()` rebuild). **Digit:** dot fills, scale 0.4 → 1, 160 ms `SETTLE`. **Backspace:** 1 → 0.7 → 1, back to outline.
- **Verifying** (PBKDF2): dots breathe, alpha 1 ↔ 0.5, 600 ms cycle, replacing "בודק…".
- **Wrong:** dots turn danger (100 ms), row shakes ±8 dp (§4.6), dots empty last → first (30 ms stagger), message fades in.
- **Correct:** dots turn success with a 1 → 1.15 → 1 pulse (200 ms); `refresh()` runs *behind* the overlay; overlay exits (alpha → 0, translationY → −24 dp, 300 ms `EXIT`); `lockView = null` only at the end.
- **Lockout:** keypad alpha animates over 200 ms.

### 4.8 Subscription login · S–M · low risk
- **Entrance** (first show): emblem 0.9 → 1 plus alpha (400 ms), title +80 ms, card rises 16 dp with fade at +160 ms.
- **Busy:** a 20 dp Canvas spinner in the button replaces "רגע…" (arc 30°–270°, 1000 ms per turn); the button keeps its width.
- **Error:** card shakes, message fades in, haptic.
- **Success:** spinner → drawn check (200 ms), button primary → success, 400 ms hold, overlay exits like the lock. Replaces the "ברוכים הבאים!" toast.

### 4.9 Swipe and pull: deliberately not proposed
Pull-to-refresh is meaningless offline. Swipe-to-complete is undiscoverable for this audience, conflicts with ScrollView intercept and the system back gesture, and causes accidental completions; check, long-press and undo cover it. Only change: tint the overscroll `EdgeEffect` with `p.primary` (API 29+).

---

## 5. Delight moments (each once, restrained, abstract: no figures)

| # | Moment | Trigger and once-rule | Choreography | Animations off |
|---|---|---|---|---|
| D1 | **Wedding day:** "מזל טוב! היום החתונה" | `days == 0`, first unlocked Home that day (`prefs` key holds the date) | The countdown box crossfades (300 ms) to the rings emblem, drawn with a trim path over 700 ms. A gold light sweep crosses the whole header (1200 ms). Then 16 gold sparkles fall slowly from the header's top edge across its width over 2.4 s (40 dp fall, ±6 dp sway, fade). Header text uses the copy team's wording. One haptic. | Static rings emblem and header text |
| D2 | **Milestones:** 30 / 7 / 1 days ("בעוד שבוע החתונה…", "מחר החתונה. מזל טוב!") | First view on that day | Digit roll (§3.1), then a shimmer, then the copy card fades in and rises 8 dp (300 ms) | Card shown |
| D3 | **All tasks done:** "ברוך ה', כל המשימות הושלמו" | Open tasks go 1 → 0 during the completion handler | After the collapse, the empty-state gold bubble draws its circle (500 ms sweep), then the check (250 ms). A 10-particle burst comes from the bubble (900 ms). The title rises 8 dp. Its haptic replaces the per-task one. | Static empty state |
| D4 | **Commitment fully paid:** a "שולם במלואו" stamp | Remaining goes from >0 to ≤0 (from the value memory) | The stamp (2 dp gold border, gold text, rotated −6°, top-end corner of the amounts card) goes scale 1.25 → 1 and alpha 0 → 1 over 260 ms `SETTLE`. Haptic at impact, 8-particle burst (700 ms). Afterwards it stays as a **static** stamp on fully paid commitments. | Static stamp |
| D5 | **Everything paid:** "כל הספקים שולמו. החשבון סגור, בשעה טובה." | Total remaining goes from >0 to 0, with commitments >0 | The "יתרה" tile ticks to ₪0 and the paid arc closes. The ring stroke pulses once in gold (stroke width 1 → 1.2 → 1, 400 ms). The tile caption crossfades to the copy text. A 12-particle burst comes from the ring centre. | Caption only |
| D6 | **First open after login** | Once per login | The two rings of `ic_rings` stroke-draw (trim path, 800 ms `ENTER`, the second starting 150 ms later), then the gold fill fades in (200 ms). This plays before §4.8's card. Needs a stroke version of the icon from the visual team (`AnimatedVectorDrawable`). | Static emblem |

Effort: D2, D5 S (reuse); D1, D3, D4, D6 M. Risk low, except D4 (depends on the dialog reordering). The shared **sparkle burst** (≤16 four-point gold stars) is a non-clickable overlay on `root` that removes itself.

```kotlin
class SparkleBurst(ctx: Context, private val cx: Float, private val cy: Float, color: Int, private val dp: Float, n: Int = 12) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val r = java.util.Random(7)                         // fixed seed: same calm pattern every time
    private val ang = FloatArray(n) { Math.toRadians(i(it, n)).toFloat() }
    private val dist = FloatArray(n) { dp * (36 + r.nextInt(36)) }   // 36–72 dp
    private val size = FloatArray(n) { dp * (3 + r.nextInt(3)) }     // 3–5 dp
    private val delay = FloatArray(n) { r.nextFloat() * .15f }
    private val star = Path(); private var t = 0f
    private fun i(k: Int, n: Int) = k * 360.0 / n + r.nextInt(18) - 9
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun play(root: FrameLayout) {
        if (!Motion.on) return
        root.addView(this, FrameLayout.LayoutParams(MATCH, MATCH))
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900; interpolator = LinearInterpolator()
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { root.removeView(this@SparkleBurst) } })
        }.start()
    }
    override fun onDraw(c: Canvas) = ang.indices.forEach { k ->
        val lt = ((t - delay[k]) / (1 - delay[k])).coerceIn(0f, 1f); if (lt == 0f) return@forEach
        val e = Motion.ENTER.getInterpolation(lt)
        val x = cx + cos(ang[k]) * dist[k] * e; val y = cy + sin(ang[k]) * dist[k] * e + dp * 10 * lt * lt  // gentle settle down
        val s = size[k] * sin(PI * lt).toFloat()
        paint.alpha = (255 * (1 - lt * lt)).toInt()
        star.reset(); star.moveTo(x, y - 2 * s)                    // 4-point star
        for ((dx, dy) in listOf(.5f to -.5f, 2f to 0f, .5f to .5f, 0f to 2f, -.5f to .5f, -2f to 0f, -.5f to -.5f)) star.lineTo(x + dx * s, y + dy * s)
        star.close(); c.drawPath(star, paint)
    }
}
```

---

## 6. Haptics map

All through `Motion.haptic(view, Event)`, which checks `SDK_INT` and an in-app "רטט" switch and never uses `FLAG_IGNORE_GLOBAL_SETTING`. **One haptic per action, only for meaningful outcomes.**

| Event | API 30+ | API 26–29 |
|---|---|---|
| Task done, payment saved, PIN or login success | `CONFIRM` | `VIRTUAL_KEY` |
| Delight impact (D1, D3, D4, D5); replaces the action's own haptic | `CONFIRM` | `VIRTUAL_KEY` |
| Task reopened, undo, urgency changed | `CLOCK_TICK` (`SEGMENT_TICK` on 34+) | `CLOCK_TICK` |
| PIN digit or backspace | `KEYBOARD_TAP` | `KEYBOARD_TAP` |
| PIN wrong, login refused | `REJECT` | `LONG_PRESS` |
| Form validation error | `REJECT` | none (the shake and text carry it) |
| Long-press menu | the system's automatic `LONG_PRESS`; add nothing | same |
| Tabs, FAB, buttons, navigation, filters | **none** (avoids fatigue) | none |

---

## 7. Effort and risk summary

| Item | Effort | Risk | Note |
|---|---|---|---|
| Motion.kt, press animators, haptics | S | Low | Foundation for everything else |
| Screen transitions | M | Med | `keepView` reuse, touch swallowing, the FAB |
| Task completion and undo bar | M | Med | Debounced refresh; the undo bar is a new component |
| Value memory and ticks | S | Low | One helper in `Ui` |
| Ring, digit roll, shimmer, stagger | M | Low | Three custom Views |
| PIN and login | M | Low | `renderDots` refactor |
| Stamp, sparkles, D1–D6 | M | Low–Med | D4 depends on reordering the dialogs |
| Urgency highlight, LayoutTransition | S | Low | |

---

## 8. Top 10 by impact ÷ effort

1. **Motion.kt foundation:** gate, curves, `press()`, `Motion.haptic` (S). Everything depends on it.
2. **Push/pop/tab transitions with touch swallowing** (M). Largest perceived-quality jump; fixes double-push.
3. **Task completion and undo bar** (M). Most frequent action; undo protects older users.
4. **Error shake and scroll-to-error** in `TextInput.error()`/`Picker.error()` (S). Every form benefits.
5. **Value memory and money ticks**, plus progress bars moving to new values (S).
6. **"שולם במלואו" stamp and sparkles (D4)** with the dialog reordering (M). Emotional peak of the money flow.
7. **PIN dots and lock exit** (M). Seen on every open.
8. **Home: first-unlock stagger, digit roll, one-time shimmer** (M).
9. **Wedding day (D1) and all tasks done (D3)** (M, reuses the burst).
10. **Budget ring sweep** (M), or animate the existing `Bar` (S).

**Next:** D2, D5, D6, login polish, urgency highlight, form `LayoutTransition`, and the faded theme restart.
