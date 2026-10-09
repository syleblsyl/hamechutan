package il.hamechutan.app.ui

import android.view.View
import android.widget.ScrollView
import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.platform.App

data class TopAction(val icon: Int, val label: String, val onClick: () -> Unit)
data class Fab(val icon: Int, val label: String?, val onClick: () -> Unit)

enum class Tab(val label: String, val icon: Int) {
    HOME("ראשי", il.hamechutan.app.R.drawable.ic_home),
    TASKS("משימות", il.hamechutan.app.R.drawable.ic_tasks),
    SUPPLIERS("ספקים", il.hamechutan.app.R.drawable.ic_suppliers),
    EXPENSES("הוצאות", il.hamechutan.app.R.drawable.ic_wallet),
    MORE("עוד", il.hamechutan.app.R.drawable.ic_more)
}

/** A screen in the single-activity navigation stack. */
abstract class Screen(val act: MainActivity) {
    val app: App get() = act.app
    val repo: Repo get() = act.app.repo
    val ui: Ui get() = act.ui
    val p: Palette get() = act.ui.p

    abstract val title: String
    open val subtitle: String? get() = null

    open fun actions(): List<TopAction> = emptyList()
    open fun fab(): Fab? = null

    /** Builds the screen content. Called on every display (fresh data) unless [keepView] is true. */
    abstract fun build(): View

    /** Forms keep their view (and the user's typing) when returning from a child screen. */
    open val keepView: Boolean get() = false
    internal var cachedView: View? = null

    /** Return true if the back press was consumed. */
    open fun onBack(): Boolean = false

    /** Forms report unsaved changes so leaving asks for confirmation. */
    open fun isDirty(): Boolean = false

    internal var savedScroll = 0
    internal var scrollView: ScrollView? = null

    fun refresh() = act.refresh(this)
    fun push(s: Screen) = act.push(s)
    fun close() = act.pop()
}
