package il.hamechutan.app.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/** Restrained, dignified palette: deep navy + muted gold on warm neutrals. */
class Palette(val dark: Boolean) {
    val bg = if (dark) c("#121417") else c("#F5F3EE")
    val surface = if (dark) c("#1B1F24") else c("#FFFFFF")
    val surfaceAlt = if (dark) c("#232830") else c("#F0EDE6")
    val primary = if (dark) c("#A9C3E6") else c("#1F3A5F")
    val onPrimary = if (dark) c("#10243D") else c("#FFFFFF")
    val primarySoft = if (dark) c("#22344A") else c("#E3EAF3")
    val gold = if (dark) c("#D9B26A") else c("#8C6D2F")
    val goldSoft = if (dark) c("#3A3122") else c("#F4EBD9")
    val text = if (dark) c("#ECEDEF") else c("#1B1F24")
    val text2 = if (dark) c("#A9B0BA") else c("#5B6470")
    val text3 = if (dark) c("#7D8590") else c("#8A919B")
    val divider = if (dark) c("#2C323B") else c("#E6E2D9")
    val outline = if (dark) c("#3A414B") else c("#D5D0C4")
    val success = if (dark) c("#7CCB9A") else c("#2E7D4F")
    val successSoft = if (dark) c("#1E3327") else c("#E3F1E8")
    val warning = if (dark) c("#F0B45C") else c("#9A5800")
    val warningSoft = if (dark) c("#3A2E1C") else c("#FBEFD9")
    val danger = if (dark) c("#F2A29B") else c("#B3261E")
    val dangerSoft = if (dark) c("#3D2220") else c("#F9E3E1")
    val info = if (dark) c("#9CC3EA") else c("#2B5C8A")
    val infoSoft = if (dark) c("#1E2D3D") else c("#E2ECF6")
    val ripple = if (dark) c("#33FFFFFF") else c("#1F1F3A5F")
    val scrim = c("#99000000")

    companion object { private fun c(s: String) = Color.parseColor(s) }
}

object Fonts {
    var regular: Typeface = Typeface.DEFAULT
    var medium: Typeface = Typeface.DEFAULT
    var semibold: Typeface = Typeface.DEFAULT_BOLD
    var bold: Typeface = Typeface.DEFAULT_BOLD
    private var loaded = false

    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            fun w(weight: Int) = Typeface.Builder(ctx.assets, "fonts/Heebo.ttf")
                .setFontVariationSettings("'wght' $weight").build()
            regular = w(400) ?: Typeface.DEFAULT
            medium = w(500) ?: regular
            semibold = w(600) ?: Typeface.DEFAULT_BOLD
            bold = w(700) ?: Typeface.DEFAULT_BOLD
        } catch (e: Exception) {
            regular = Typeface.DEFAULT; medium = Typeface.DEFAULT; semibold = Typeface.DEFAULT_BOLD; bold = Typeface.DEFAULT_BOLD
        }
    }
}

enum class TS(val sp: Float, val weight: Int) {
    DISPLAY(26f, 700), TITLE(20f, 700), SUBTITLE(16.5f, 600), BODY(15f, 400), BODY_STRONG(15f, 600),
    CAPTION(13f, 400), CAPTION_STRONG(13f, 600), LABEL(12f, 600), AMOUNT_L(24f, 700), AMOUNT(17f, 700), SMALL(11.5f, 500);

    val typeface: Typeface get() = when {
        weight >= 700 -> Fonts.bold
        weight >= 600 -> Fonts.semibold
        weight >= 500 -> Fonts.medium
        else -> Fonts.regular
    }
}

object ThemeMode {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    fun isDark(ctx: Context, mode: String?): Boolean = when (mode) {
        DARK -> true
        LIGHT -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
