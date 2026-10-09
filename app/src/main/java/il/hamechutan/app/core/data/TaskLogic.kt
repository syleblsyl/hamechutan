package il.hamechutan.app.core.data

import il.hamechutan.app.core.model.Priority
import il.hamechutan.app.core.model.Task
import il.hamechutan.app.core.model.TaskBucket
import il.hamechutan.app.core.model.TaskView
import il.hamechutan.app.core.util.Dates
import java.time.LocalDateTime

/** Pure task classification logic ("what's left to do?"). */
object TaskLogic {

    fun isOverdue(t: Task, now: LocalDateTime): Boolean {
        if (!t.status.isActive) return false
        val d = Dates.parseDate(t.dueDate) ?: return false
        val today = now.toLocalDate()
        if (d.isBefore(today)) return true
        if (d == today) {
            val time = Dates.parseTime(t.dueTime) ?: return false
            return time.isBefore(now.toLocalTime())
        }
        return false
    }

    fun bucketOf(t: Task, now: LocalDateTime): TaskBucket {
        if (isOverdue(t, now)) return TaskBucket.OVERDUE
        if (t.priority == Priority.URGENT) return TaskBucket.URGENT
        val d = Dates.parseDate(t.dueDate) ?: return TaskBucket.LATER
        val today = now.toLocalDate()
        return when {
            d == today -> TaskBucket.TODAY
            !d.isAfter(today.plusDays(7)) -> TaskBucket.WEEK
            else -> TaskBucket.LATER
        }
    }

    val comparator: Comparator<TaskView> = Comparator { a, b ->
        val da = a.task.dueDate ?: "9999-99-99"
        val dbb = b.task.dueDate ?: "9999-99-99"
        if (da != dbb) return@Comparator da.compareTo(dbb)
        val ta = a.task.dueTime ?: "99:99"
        val tb = b.task.dueTime ?: "99:99"
        if (ta != tb) return@Comparator ta.compareTo(tb)
        if (a.task.priority != b.task.priority) return@Comparator b.task.priority.value - a.task.priority.value
        a.task.id.compareTo(b.task.id)
    }

    /** Active tasks grouped into buckets, in display order. Empty buckets are included. */
    fun bucketize(tasks: List<TaskView>, now: LocalDateTime): LinkedHashMap<TaskBucket, List<TaskView>> {
        val map = LinkedHashMap<TaskBucket, MutableList<TaskView>>()
        TaskBucket.values().forEach { map[it] = mutableListOf() }
        tasks.filter { it.task.status.isActive }.forEach { map[bucketOf(it.task, now)]!!.add(it) }
        val out = LinkedHashMap<TaskBucket, List<TaskView>>()
        map.forEach { (k, v) -> out[k] = v.sortedWith(comparator) }
        return out
    }
}
