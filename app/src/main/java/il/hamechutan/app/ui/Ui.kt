package il.hamechutan.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import il.hamechutan.app.R
import il.hamechutan.app.core.model.ValidationException
import il.hamechutan.app.core.util.Dates
import il.hamechutan.app.core.util.HebrewDate
import il.hamechutan.app.core.util.Money
import java.time.LocalDate
import java.time.LocalTime

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

enum class BtnKind { PRIMARY, SECONDARY, TONAL, TEXT, DANGER, DANGER_TEXT }
enum class Tone { INFO, SUCCESS, WARNING, DANGER, NEUTRAL, GOLD }

/** Small UI toolkit: every screen is built in code with these helpers (no AndroidX available). */
class Ui(val ctx: Context, val p: Palette, val scale: Float, val hebrewDates: Boolean) {

    fun dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(v: Number): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics)

    // ------------------------------------------------------------ drawables

    fun shape(color: Int, radius: Float = 16f, stroke: Int? = null, strokeW: Float = 1f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dpf(radius)
            if (stroke != null) setStroke(dp(strokeW), stroke)
        }

    fun ripple(content: Drawable?, radius: Float = 16f): Drawable =
        RippleDrawable(ColorStateList.valueOf(p.ripple), content, shape(0xFFFFFFFF.toInt(), radius))

    fun tone(t: Tone): Pair<Int, Int> = when (t) {
        Tone.INFO -> p.info to p.infoSoft
        Tone.SUCCESS -> p.success to p.successSoft
        Tone.WARNING -> p.warning to p.warningSoft
        Tone.DANGER -> p.danger to p.dangerSoft
        Tone.NEUTRAL -> p.text2 to p.surfaceAlt
        Tone.GOLD -> p.gold to p.goldSoft
    }

    // ------------------------------------------------------------ layout params

    fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0, gravity: Int = -1) =
        LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = dp(top); bottomMargin = dp(bottom); marginStart = dp(start); marginEnd = dp(end)
            if (gravity != -1) this.gravity = gravity
        }

    fun flp(w: Int = MATCH, h: Int = WRAP, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

    // ------------------------------------------------------------ basic views

    fun tv(text: CharSequence?, style: TS = TS.BODY, color: Int = p.text, lines: Int = 0): TextView = TextView(ctx).apply {
        this.text = text ?: ""
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp * scale)
        typeface = style.typeface
        setTextColor(color)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        setLineSpacing(0f, 1.08f)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    fun col(padH: Int = 0, padV: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    fun space(h: Int = 8): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    fun hspace(w: Int = 8): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(dp(w), 1) }

    fun weightSpace(): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun divider(insetStart: Int = 0): View = View(ctx).apply {
        setBackgroundColor(p.divider)
        layoutParams = lp(MATCH, dp(1).coerceAtLeast(1)).apply { marginStart = dp(insetStart) }
    }

    fun icon(res: Int, color: Int = p.text2, size: Int = 24): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Icon inside a soft circle (list leading). */
    fun iconBubble(res: Int, fg: Int = p.primary, bg: Int = p.primarySoft, size: Int = 40): FrameLayout = FrameLayout(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        addView(ImageView(ctx).apply {
            setImageResource(res); imageTintList = ColorStateList.valueOf(fg)
        }, flp(dp(size * 0.55f), dp(size * 0.55f), Gravity.CENTER))
    }

    fun iconButton(res: Int, desc: String, color: Int = p.text2, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        contentDescription = desc
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = RippleDrawable(ColorStateList.valueOf(p.ripple), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
        tooltipText = desc
    }

    fun button(text: String, kind: BtnKind = BtnKind.PRIMARY, iconRes: Int? = null, small: Boolean = false, onClick: () -> Unit): TextView {
        val (fg, bgc, stroke) = when (kind) {
            BtnKind.PRIMARY -> Triple(p.onPrimary, p.primary, null)
            BtnKind.SECONDARY -> Triple(p.primary, p.surface, p.outline)
            BtnKind.TONAL -> Triple(p.primary, p.primarySoft, null)
            BtnKind.TEXT -> Triple(p.primary, 0, null)
            BtnKind.DANGER -> Triple(0xFFFFFFFF.toInt(), p.danger, null)
            BtnKind.DANGER_TEXT -> Triple(p.danger, 0, null)
        }
        return tv(text, if (small) TS.CAPTION_STRONG else TS.BODY_STRONG, fg).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            val h = if (small) 8 else 12
            setPadding(dp(if (small) 12 else 18), dp(h), dp(if (small) 12 else 18), dp(h))
            minHeight = dp(if (small) 36 else 48)
            background = ripple(if (bgc == 0 && stroke == null) null else shape(bgc, 12f, stroke), 12f)
            if (iconRes != null) {
                val d = ctx.getDrawable(iconRes)!!.mutate()
                d.setTint(fg)
                val s = dp(if (small) 16 else 20)
                d.setBounds(0, 0, s, s)
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(8)
            }
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    fun chip(text: String, selected: Boolean, count: Int? = null, onClick: () -> Unit): TextView =
        tv(if (count != null) "$text · $count" else text, TS.CAPTION_STRONG, if (selected) p.onPrimary else p.text2).apply {
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = ripple(shape(if (selected) p.primary else p.surface, 18f, if (selected) null else p.outline), 18f)
            maxLines = 1
            setOnClickListener { onClick() }
            layoutParams = lp(WRAP, WRAP, end = 8)
        }

    fun chipRow(chips: List<View>): HorizontalScrollView = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        val r = row()
        chips.forEach { r.addView(it) }
        addView(r)
    }

    fun badge(text: String, t: Tone): TextView {
        val (fg, bg) = tone(t)
        return tv(text, TS.SMALL, fg).apply {
            setPadding(dp(8), dp(2), dp(8), dp(3))
            background = shape(bg, 10f)
            maxLines = 1
        }
    }

    fun card(padH: Int = 16, padV: Int = 14, onClick: (() -> Unit)? = null, color: Int = p.surface, stroke: Int? = p.divider): LinearLayout = col(padH, padV).apply {
        val base = shape(color, 16f, stroke)
        background = if (onClick != null) ripple(base) else base
        layoutParams = lp(MATCH, WRAP, bottom = 12)
        if (onClick != null) { isClickable = true; setOnClickListener { onClick() } }
    }

    /** Card container whose children are separated rows (padding handled by rows). */
    fun listCard(): LinearLayout = col().apply {
        background = shape(p.surface, 16f, p.divider)
        layoutParams = lp(MATCH, WRAP, bottom = 12)
        clipToOutline = true
    }

    fun sectionHeader(title: String, actionText: String? = null, action: (() -> Unit)? = null): LinearLayout = row().apply {
        setPadding(dp(4), dp(10), dp(4), dp(8))
        addView(tv(title, TS.SUBTITLE, p.text), lp(0, WRAP, 1f))
        if (actionText != null && action != null) {
            addView(tv(actionText, TS.CAPTION_STRONG, p.primary).apply {
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = ripple(null, 10f)
                setOnClickListener { action() }
            })
        }
    }

    /** A tappable list row. */
    fun listRow(
        title: CharSequence, subtitle: CharSequence? = null, iconRes: Int? = null, iconTone: Tone = Tone.INFO,
        trailing: View? = null, third: CharSequence? = null, titleColor: Int = p.text, chevron: Boolean = false,
        onClick: (() -> Unit)? = null
    ): LinearLayout = row().apply {
        setPadding(dp(16), dp(12), dp(16), dp(12))
        minimumHeight = dp(56)
        if (iconRes != null) {
            val (fg, bg) = tone(iconTone)
            addView(iconBubble(iconRes, fg, bg), lp(dp(40), dp(40), end = 12))
        }
        val mid = col()
        mid.addView(tv(title, TS.BODY_STRONG, titleColor, 2))
        if (!subtitle.isNullOrEmpty()) mid.addView(tv(subtitle, TS.CAPTION, p.text2, 2), lp(top = 2))
        if (!third.isNullOrEmpty()) mid.addView(tv(third, TS.CAPTION, p.text3, 2), lp(top = 2))
        addView(mid, lp(0, WRAP, 1f))
        if (trailing != null) addView(trailing, lp(WRAP, WRAP, start = 8))
        if (chevron) addView(icon(R.drawable.ic_chevron, p.text3, 20), lp(dp(20), dp(20), start = 4))
        if (onClick != null) {
            background = ripple(null, 0f)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Trailing column: amount + optional badge/caption. */
    fun trailingAmount(amount: String, color: Int = p.text, caption: String? = null, badge: TextView? = null): LinearLayout = col().apply {
        gravity = Gravity.END
        addView(tv(amount, TS.BODY_STRONG, color).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
        if (caption != null) addView(tv(caption, TS.SMALL, p.text3).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END })
        if (badge != null) addView(badge, lp(WRAP, WRAP, top = 4, gravity = Gravity.END))
    }

    fun kv(label: String, value: CharSequence, valueColor: Int = p.text, strong: Boolean = false): LinearLayout = row(Gravity.TOP).apply {
        setPadding(0, dp(6), 0, dp(6))
        addView(tv(label, TS.BODY, p.text2), lp(0, WRAP, 1f))
        addView(tv(value, if (strong) TS.BODY_STRONG else TS.BODY, valueColor).apply { textAlignment = View.TEXT_ALIGNMENT_VIEW_END }, lp(0, WRAP, 1.3f, start = 8))
    }

    fun banner(text: CharSequence, t: Tone, iconRes: Int = R.drawable.ic_info, actionText: String? = null, action: (() -> Unit)? = null): LinearLayout {
        val (fg, bg) = tone(t)
        return row(Gravity.TOP).apply {
            background = shape(bg, 14f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(MATCH, WRAP, bottom = 12)
            addView(icon(iconRes, fg, 20), lp(dp(20), dp(20), end = 10, top = 1))
            val c = col()
            c.addView(tv(text, TS.CAPTION_STRONG, fg))
            if (actionText != null && action != null) {
                c.addView(tv(actionText, TS.CAPTION_STRONG, p.primary).apply {
                    setPadding(0, dp(8), dp(8), dp(2)); paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    setOnClickListener { action() }
                })
            }
            addView(c, lp(0, WRAP, 1f))
        }
    }

    fun emptyState(iconRes: Int, title: String, text: String?, actionText: String? = null, action: (() -> Unit)? = null): LinearLayout = col(24, 28).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        addView(iconBubble(iconRes, p.gold, p.goldSoft, 64), lp(dp(64), dp(64), gravity = Gravity.CENTER_HORIZONTAL))
        addView(tv(title, TS.SUBTITLE).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(top = 14))
        if (text != null) addView(tv(text, TS.CAPTION, p.text2).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER }, lp(top = 6))
        if (actionText != null && action != null) addView(button(actionText, BtnKind.TONAL, R.drawable.ic_add, onClick = action), lp(WRAP, WRAP, top = 16, gravity = Gravity.CENTER_HORIZONTAL))
    }

    fun scroll(content: View): ScrollView = ScrollView(ctx).apply {
        isFillViewport = true
        addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    /** Standard page: scrollable column with side gutters and room for a FAB at the bottom. */
    fun page(bottomPad: Int = 96): Pair<ScrollView, LinearLayout> {
        val c = col(16, 8)
        c.setPadding(dp(16), dp(8), dp(16), dp(bottomPad))
        return scroll(c) to c
    }

    fun progressBar(fractionStrong: Float, fractionLight: Float = 0f, strong: Int = p.primary, light: Int = p.primarySoft, track: Int = p.surfaceAlt, height: Int = 8): View =
        Bar(ctx, fractionStrong, fractionLight, strong, light, track).apply { layoutParams = lp(MATCH, dp(height)) }

    class Bar(ctx: Context, private val strongF: Float, private val lightF: Float, private val strong: Int, private val light: Int, private val track: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2
            paint.color = track; r.set(0f, 0f, w, h); canvas.drawRoundRect(r, rad, rad, paint)
            val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
            fun seg(f: Float, c: Int) {
                if (f <= 0f) return
                val len = w * f.coerceIn(0f, 1f)
                paint.color = c
                if (rtl) r.set(w - len, 0f, w, h) else r.set(0f, 0f, len, h)
                canvas.drawRoundRect(r, rad, rad, paint)
            }
            seg(lightF, light); seg(strongF, strong)
        }
    }

    fun statTile(label: String, value: String, sub: String? = null, valueColor: Int = p.text, iconRes: Int? = null, onClick: (() -> Unit)? = null): LinearLayout =
        card(14, 12, onClick).apply {
            layoutParams = lp(0, MATCH, 1f, bottom = 0)
            val head = row()
            if (iconRes != null) head.addView(icon(iconRes, p.text3, 16), lp(dp(16), dp(16), end = 6))
            head.addView(tv(label, TS.CAPTION, p.text2, 1))
            addView(head)
            addView(tv(value, TS.AMOUNT, valueColor, 1), lp(top = 4))
            if (sub != null) addView(tv(sub, TS.SMALL, p.text3, 2), lp(top = 2))
        }

    fun tileRow(vararg tiles: View): LinearLayout = row(Gravity.FILL_VERTICAL).apply {
        layoutParams = lp(MATCH, WRAP, bottom = 10)
        tiles.forEachIndexed { i, t ->
            if (i > 0) addView(hspace(10))
            addView(t)
        }
    }

    // ------------------------------------------------------------ form fields

    private fun inputBg(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(p.surface, 12f, p.primary, 2f))
        addState(intArrayOf(), shape(p.surface, 12f, p.outline, 1f))
    }

    fun fieldLabel(label: String, required: Boolean = false): TextView =
        tv(if (required) "$label *" else label, TS.CAPTION_STRONG, p.text2).apply { setPadding(dp(2), 0, dp(2), dp(6)) }

    inner class TextInput(val root: LinearLayout, val edit: EditText, private val err: TextView) {
        val value: String get() = edit.text.toString()
        val clean: String? get() = value.trim().ifEmpty { null }
        fun error(msg: String?) {
            err.text = msg ?: ""; err.visibility = if (msg == null) View.GONE else View.VISIBLE
            if (msg != null) edit.requestFocus()
        }
        fun set(v: String?) { edit.setText(v ?: "") }
    }

    fun textField(
        label: String, value: String?, hint: String? = null,
        type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
        lines: Int = 1, required: Boolean = false, helper: String? = null
    ): TextInput {
        val root = col().apply { layoutParams = lp(MATCH, WRAP, bottom = 14) }
        root.addView(fieldLabel(label, required))
        val edit = EditText(ctx).apply {
            setText(value ?: "")
            this.hint = hint
            setHintTextColor(p.text3)
            setTextColor(p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TS.BODY.sp * scale)
            typeface = Fonts.regular
            background = inputBg()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            // setSingleLine() must come BEFORE inputType, otherwise it replaces password masking.
            if (lines > 1) {
                isSingleLine = false
                inputType = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = lines; gravity = Gravity.TOP or Gravity.START
            } else {
                isSingleLine = true
                inputType = type
            }
            minHeight = dp(48)
        }
        root.addView(edit, lp(MATCH, WRAP))
        if (helper != null) root.addView(tv(helper, TS.SMALL, p.text3), lp(top = 4))
        val err = tv("", TS.CAPTION_STRONG, p.danger).apply { visibility = View.GONE; setPadding(dp(2), dp(4), 0, 0) }
        root.addView(err)
        return TextInput(root, edit, err)
    }

    fun moneyField(label: String, agorot: Long?, required: Boolean = false, helper: String? = null): TextInput =
        textField(label, agorot?.let { Money.inputValue(it) }, "0", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL, required = required, helper = helper)

    fun phoneField(label: String, value: String?): TextInput = textField(label, value, "050-0000000", InputType.TYPE_CLASS_PHONE)

    /** Reads a money field; shows the error on the field and returns null when invalid. Empty -> Empty. */
    fun readMoney(f: TextInput, required: Boolean): Money.Parse {
        val r = Money.parse(f.value)
        when {
            r is Money.Parse.Error -> f.error(r.message)
            r is Money.Parse.Empty && required -> f.error("יש להזין סכום")
            r is Money.Parse.Ok && required && r.agorot == 0L -> { f.error("יש להזין סכום גדול מאפס"); return Money.Parse.Error("") }
            else -> f.error(null)
        }
        return r
    }

    /** A field that opens a chooser (looks like an input with a chevron). */
    inner class Picker(val root: LinearLayout, private val valueView: TextView, private val clearView: ImageView, private val err: TextView, private val placeholder: String) {
        var onClear: (() -> Unit)? = null
        fun set(text: String?) {
            valueView.text = text ?: placeholder
            valueView.setTextColor(if (text == null) p.text3 else p.text)
            clearView.visibility = if (text != null && onClear != null) View.VISIBLE else View.GONE
        }
        fun error(msg: String?) { err.text = msg ?: ""; err.visibility = if (msg == null) View.GONE else View.VISIBLE }
    }

    fun picker(label: String, placeholder: String, iconRes: Int = R.drawable.ic_expand, required: Boolean = false, helper: String? = null, clearable: Boolean = false, onClick: () -> Unit): Picker {
        val root = col().apply { layoutParams = lp(MATCH, WRAP, bottom = 14) }
        root.addView(fieldLabel(label, required))
        val box = row().apply {
            background = ripple(shape(p.surface, 12f, p.outline), 12f)
            setPadding(dp(14), dp(4), dp(6), dp(4))
            minimumHeight = dp(48)
            isClickable = true
            setOnClickListener { onClick() }
        }
        val value = tv(placeholder, TS.BODY, p.text3, 2)
        box.addView(value, lp(0, WRAP, 1f))
        val clear = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); imageTintList = ColorStateList.valueOf(p.text3)
            setPadding(dp(8), dp(8), dp(8), dp(8)); contentDescription = "ניקוי"; visibility = View.GONE
            background = RippleDrawable(ColorStateList.valueOf(p.ripple), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(-1) })
        }
        box.addView(clear, LinearLayout.LayoutParams(dp(36), dp(36)))
        box.addView(icon(iconRes, p.text3, 20), lp(dp(20), dp(20), start = 2, end = 6))
        root.addView(box)
        if (helper != null) root.addView(tv(helper, TS.SMALL, p.text3), lp(top = 4))
        val err = tv("", TS.CAPTION_STRONG, p.danger).apply { visibility = View.GONE; setPadding(dp(2), dp(4), 0, 0) }
        root.addView(err)
        val pk = Picker(root, value, clear, err, placeholder)
        if (clearable) clear.setOnClickListener { pk.onClear?.invoke() }
        return pk
    }

    fun dateLabel(iso: String?): String? {
        val d = Dates.parseDate(iso) ?: return null
        val heb = if (hebrewDates) " · " + HebrewDate.fromGregorian(d).format() else ""
        return Dates.displayWithDay(d) + heb
    }

    /** Date picker field holding an ISO date. */
    inner class DateInput(val picker: Picker, var iso: String?, private val onChange: ((String?) -> Unit)?) {
        fun set(v: String?) { iso = v; picker.set(dateLabel(v)); onChange?.invoke(v) }
    }

    fun dateField(label: String, iso: String?, required: Boolean = false, clearable: Boolean = true, helper: String? = null, onChange: ((String?) -> Unit)? = null): DateInput {
        lateinit var di: DateInput
        val pk = picker(label, "בחירת תאריך", R.drawable.ic_calendar, required, helper, clearable && !required) {
            val cur = Dates.parseDate(di.iso) ?: LocalDate.now()
            DatePickerDialog(ctx, { _, y, m, d -> di.set(Dates.iso(LocalDate.of(y, m + 1, d))) }, cur.year, cur.monthValue - 1, cur.dayOfMonth).show()
        }
        di = DateInput(pk, iso, onChange)
        pk.onClear = if (clearable && !required) ({ di.set(null) }) else null
        pk.set(dateLabel(iso))
        return di
    }

    inner class TimeInput(val picker: Picker, var iso: String?) {
        fun set(v: String?) { iso = v; picker.set(v?.let { Dates.displayTime(it) }) }
    }

    fun timeField(label: String, iso: String?, helper: String? = null): TimeInput {
        lateinit var ti: TimeInput
        val pk = picker(label, "ללא שעה", R.drawable.ic_clock, false, helper, true) {
            val cur = Dates.parseTime(ti.iso) ?: LocalTime.of(LocalTime.now().hour, 0)
            TimePickerDialog(ctx, { _, h, m -> ti.set(Dates.iso(LocalTime.of(h, m))) }, cur.hour, cur.minute, true).show()
        }
        ti = TimeInput(pk, iso)
        pk.onClear = { ti.set(null) }
        pk.set(iso?.let { Dates.displayTime(it) })
        return ti
    }

    /** Single-choice picker over a list of (id, label). */
    inner class Choice<T>(val picker: Picker, var value: T?, private val label: (T) -> String) {
        fun set(v: T?) { value = v; picker.set(v?.let(label)) }
    }

    fun <T> choiceField(
        label: String, placeholder: String, options: () -> List<T>, value: T?, labelOf: (T) -> String,
        required: Boolean = false, helper: String? = null, extraAction: Pair<String, () -> Unit>? = null, onChange: ((T?) -> Unit)? = null
    ): Choice<T> {
        lateinit var ch: Choice<T>
        val pk = picker(label, placeholder, R.drawable.ic_expand, required, helper, !required) {
            val opts = options()
            val labels = opts.map(labelOf).toMutableList()
            val extraIndex = if (extraAction != null) { labels += extraAction.first; labels.size - 1 } else -1
            val checked = opts.indexOfFirst { it == ch.value }
            AlertDialog.Builder(ctx).setTitle(label)
                .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                    d.dismiss()
                    if (which == extraIndex) extraAction!!.second() else { ch.set(opts[which]); onChange?.invoke(opts[which]) }
                }
                .setNegativeButton("סגירה", null).show()
        }
        ch = Choice(pk, value, labelOf)
        pk.onClear = if (required) null else ({ ch.set(null); onChange?.invoke(null) })
        pk.set(value?.let(labelOf))
        return ch
    }

    fun switchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit): Pair<LinearLayout, Switch> {
        val sw = Switch(ctx).apply { isChecked = checked; showText = false }
        val r = row().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(56)
            val c = col()
            c.addView(tv(title, TS.BODY_STRONG))
            if (sub != null) c.addView(tv(sub, TS.CAPTION, p.text2), lp(top = 2))
            addView(c, lp(0, WRAP, 1f, end = 12))
            addView(sw)
            background = ripple(null, 0f)
            setOnClickListener { sw.isChecked = !sw.isChecked }
        }
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        return r to sw
    }

    /** Segmented selector (equal width options). */
    fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val container = row().apply {
            background = shape(p.surfaceAlt, 12f)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = lp(MATCH, WRAP, bottom = 14)
        }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) = views.forEachIndexed { i, v ->
            v.setTextColor(if (i == sel) p.primary else p.text2)
            v.background = if (i == sel) shape(p.surface, 10f, p.divider) else null
        }
        options.forEachIndexed { i, o ->
            val v = tv(o, TS.CAPTION_STRONG, p.text2, 1).apply {
                gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER
                setPadding(dp(4), dp(9), dp(4), dp(9))
                setOnClickListener { paint(i); onSelect(i) }
            }
            views += v
            container.addView(v, lp(0, WRAP, 1f))
        }
        paint(selected)
        return container
    }

    // ------------------------------------------------------------ feedback

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    fun alert(title: String, message: CharSequence, onOk: (() -> Unit)? = null) {
        AlertDialog.Builder(ctx).setTitle(title).setMessage(message).setPositiveButton("הבנתי") { _, _ -> onOk?.invoke() }.show()
    }

    fun confirm(title: String, message: CharSequence, yes: String, no: String = "ביטול", destructive: Boolean = false, onYes: () -> Unit) {
        val d = AlertDialog.Builder(ctx).setTitle(title).setMessage(message)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton(no, null).show()
        if (destructive) d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(p.danger)
    }

    fun options(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(ctx).setTitle(title).setItems(items.toTypedArray()) { _, w -> onPick(w) }.setNegativeButton("סגירה", null).show()
    }

    /** Runs an action, turning validation and unexpected errors into Hebrew dialogs instead of crashes. */
    fun guard(action: () -> Unit) {
        try { action() } catch (e: ValidationException) {
            alert("לא ניתן לשמור", e.message ?: "")
        } catch (e: Exception) {
            android.util.Log.e("HaMechutan", "action failed", e)
            alert("אירעה שגיאה", "הפעולה לא הושלמה. הנתונים לא השתנו.\n\nפרטים: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    fun hideKeyboard(v: View?) {
        if (v == null) return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** Wraps a view in padding for use inside an AlertDialog. */
    fun dialogBody(v: View): View = ScrollView(ctx).apply {
        addView(FrameLayout(ctx).apply { setPadding(dp(22), dp(12), dp(22), dp(4)); addView(v, flp(MATCH, WRAP)) })
    }

    fun <A : Activity> activity(): A? = ctx as? A
}

fun LinearLayout.add(v: View, lp: ViewGroup.LayoutParams? = null): LinearLayout {
    if (lp != null) addView(v, lp) else addView(v)
    return this
}
