package il.hamechutan.app.core

import il.hamechutan.app.core.data.Repo
import il.hamechutan.app.core.data.Repo.ReminderState
import il.hamechutan.app.core.model.*
import il.hamechutan.app.core.util.Dates
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class TaskReminderTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private fun ms(date: String, time: String) = Dates.toMillis(LocalDate.parse(date), LocalTime.parse(time), zone)

    /** Acceptance scenario "תרחיש משימות". Today is 2026-10-09 10:00. */
    @Test fun payPhotographerTaskScenario() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", initialAgreedAgorot = 800000), null)
        val eid = repo.expensesForSupplier(sid).single().id
        val tid = repo.saveTask(TaskInput("לשלם לצלם", dueDate = "2026-10-12", dueTime = "18:00", supplierId = sid, expenseId = eid), null)
        repo.setReminder(TargetType.TASK, tid, ReminderSpec(ReminderMode.OFFSET, 60))

        val alarms = repo.plannedAlarms()
        assertEquals(1, alarms.size)
        assertEquals(ms("2026-10-12", "17:00"), alarms[0].triggerAt)

        // in task list and home dashboard (this week bucket)
        assertTrue(repo.tasks().any { it.id == tid })
        val dash = repo.dashboard()
        assertTrue(dash.buckets[TaskBucket.WEEK]!!.any { it.id == tid })
        assertEquals(1, dash.openTaskCount)
        // linked views
        assertEquals("צלם", repo.task(tid)!!.supplierName)
        assertTrue(repo.tasksForSupplier(sid).any { it.id == tid })
        assertTrue(repo.tasksForExpense(eid).any { it.id == tid })

        repo.setTaskStatus(tid, TaskStatus.DONE)
        assertEquals(TaskStatus.DONE, repo.task(tid)!!.task.status)
        assertNotNull(repo.task(tid)!!.task.completedAt)
        assertTrue(repo.dashboard().buckets.values.none { l -> l.any { it.id == tid } })
        assertEquals(0, repo.dashboard().openTaskCount)
        assertTrue("reminder of a done task is not armed", repo.plannedAlarms().isEmpty())
        assertEquals(ReminderState.INACTIVE, repo.reminderStatuses().single().state)
    }

    @Test fun fullPaymentSuggestsButDoesNotCompleteTasks() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", initialAgreedAgorot = 100000), null)
        val eid = repo.expensesForSupplier(sid).single().id
        val pay = repo.saveTask(TaskInput("לשלם לצלם", expenseId = eid), null)
        val coord = repo.saveTask(TaskInput("לתאם עם הצלם", supplierId = sid), null)
        assertEquals("supplier inferred from expense", sid, repo.task(pay)!!.task.supplierId)
        val r = repo.addPayment(PaymentInput(eid, 100000, "2026-10-09", null))
        assertEquals(0, r.remainingAfter)
        assertEquals(listOf(pay), r.relatedOpenTasks.map { it.id })
        assertEquals(TaskStatus.OPEN, repo.task(pay)!!.task.status)
        assertEquals(TaskStatus.OPEN, repo.task(coord)!!.task.status)
    }

    @Test fun taskValidation() {
        val repo = TestDb.fresh()
        val s1 = repo.saveSupplier(SupplierInput("א", initialAgreedAgorot = 100), null)
        val s2 = repo.saveSupplier(SupplierInput("ב"), null)
        val e1 = repo.expensesForSupplier(s1).single().id
        try { repo.saveTask(TaskInput("x", supplierId = s2, expenseId = e1), null); fail("mismatched supplier/expense") } catch (e: ValidationException) {}
        try { repo.saveTask(TaskInput(" "), null); fail() } catch (e: ValidationException) {}
        try { repo.saveTask(TaskInput("x", dueTime = "10:00"), null); fail("time without date") } catch (e: ValidationException) {}
        try { repo.saveTask(TaskInput("x", dueDate = "2026-02-30"), null); fail() } catch (e: ValidationException) {}
        try { repo.saveTask(TaskInput("x", supplierId = 777), null); fail() } catch (e: ValidationException) {}
    }

    @Test fun bucketsByUrgency() {
        val repo = TestDb.fresh() // 2026-10-09 10:00
        val overdue = repo.saveTask(TaskInput("באיחור", dueDate = "2026-10-08"), null)
        val overdueToday = repo.saveTask(TaskInput("באיחור היום", dueDate = "2026-10-09", dueTime = "08:00"), null)
        val urgent = repo.saveTask(TaskInput("דחוף", dueDate = "2026-11-20", priority = Priority.URGENT), null)
        val today = repo.saveTask(TaskInput("היום", dueDate = "2026-10-09", dueTime = "20:00"), null)
        val week = repo.saveTask(TaskInput("השבוע", dueDate = "2026-10-14"), null)
        val later = repo.saveTask(TaskInput("בהמשך", dueDate = "2026-12-01"), null)
        val noDate = repo.saveTask(TaskInput("ללא תאריך"), null)
        val done = repo.saveTask(TaskInput("גמור", dueDate = "2026-10-01", status = TaskStatus.DONE), null)
        val b = repo.taskBuckets()
        fun ids(k: TaskBucket) = b[k]!!.map { it.id }.toSet()
        assertEquals(setOf(overdue, overdueToday), ids(TaskBucket.OVERDUE))
        assertEquals(setOf(urgent), ids(TaskBucket.URGENT))
        assertEquals(setOf(today), ids(TaskBucket.TODAY))
        assertEquals(setOf(week), ids(TaskBucket.WEEK))
        assertEquals(setOf(later, noDate), ids(TaskBucket.LATER))
        assertTrue(b.values.none { l -> l.any { it.id == done } })
        assertEquals("date first, no date last", listOf(later, noDate), b[TaskBucket.LATER]!!.map { it.id })
    }

    @Test fun reminderFollowsEditsWithoutDuplicates() {
        val clock = MutableClock(Instant.parse("2026-10-09T07:00:00Z"))
        val repo = TestDb.fresh(clock)
        val tid = repo.saveTask(TaskInput("לאסוף שטריימל", dueDate = "2026-10-15", dueTime = "12:00"), null)
        repo.setReminder(TargetType.TASK, tid, ReminderSpec(ReminderMode.OFFSET, 1440))
        assertEquals(listOf(ms("2026-10-14", "12:00")), repo.plannedAlarms().map { it.triggerAt })

        // date change -> same single reminder with the new time
        repo.saveTask(TaskInput("לאסוף שטריימל", dueDate = "2026-10-20", dueTime = "12:00"), tid)
        val a = repo.plannedAlarms()
        assertEquals(1, a.size)
        assertEquals(ms("2026-10-19", "12:00"), a[0].triggerAt)
        assertEquals(1, repo.reminders().size)

        // mute without deleting the task
        val rid = a[0].reminderId
        repo.setReminderMuted(rid, true)
        assertTrue(repo.plannedAlarms().isEmpty())
        assertNotNull(repo.task(tid))
        assertEquals(ReminderState.MUTED, repo.reminderStatuses().single().state)
        repo.setReminderMuted(rid, false)
        assertEquals(1, repo.plannedAlarms().size)

        // fired -> not again for the same trigger
        repo.markReminderFired(rid, ms("2026-10-19", "12:00"))
        assertTrue(repo.plannedAlarms().isEmpty())
        assertEquals(ReminderState.FIRED, repo.reminderStatuses().single().state)
        // snooze -> fires again at the snooze time
        val snooze = ms("2026-10-19", "13:00")
        repo.snoozeReminder(rid, snooze)
        assertEquals(listOf(snooze), repo.plannedAlarms().map { it.triggerAt })
        // editing the date clears the snooze and plans the new trigger
        repo.saveTask(TaskInput("לאסוף שטריימל", dueDate = "2026-10-21", dueTime = "12:00"), tid)
        assertEquals(listOf(ms("2026-10-20", "12:00")), repo.plannedAlarms().map { it.triggerAt })

        // cancelling the task disarms it; deleting removes the reminder record
        repo.setTaskStatus(tid, TaskStatus.CANCELLED)
        assertTrue(repo.plannedAlarms().isEmpty())
        repo.setTaskStatus(tid, TaskStatus.OPEN)
        assertEquals(1, repo.plannedAlarms().size)
        repo.deleteTask(tid)
        assertTrue(repo.reminders().isEmpty())
        assertTrue(repo.plannedAlarms().isEmpty())
    }

    @Test fun missedAndPassedReminders() {
        val clock = MutableClock(Instant.parse("2026-10-09T07:00:00Z")) // 10:00 local
        val repo = TestDb.fresh(clock)
        val t1 = repo.saveTask(TaskInput("עבר מזמן", dueDate = "2026-10-08", dueTime = "09:00"), null)
        repo.setReminder(TargetType.TASK, t1, ReminderSpec(ReminderMode.OFFSET, 0))
        val t2 = repo.saveTask(TaskInput("החמצה קצרה", dueDate = "2026-10-09", dueTime = "08:00"), null)
        repo.setReminder(TargetType.TASK, t2, ReminderSpec(ReminderMode.OFFSET, 0))
        val t3 = repo.saveTask(TaskInput("ללא תאריך"), null)
        repo.setReminder(TargetType.TASK, t3, ReminderSpec(ReminderMode.OFFSET, 60))
        val states = repo.reminderStatuses().associate { it.reminder.targetId to it.state }
        assertEquals(ReminderState.PASSED, states[t1])
        assertEquals(ReminderState.PLANNED, states[t2])
        assertEquals(ReminderState.NO_DATE, states[t3])
        val a = repo.plannedAlarms()
        assertEquals(1, a.size)
        assertTrue("missed reminder (e.g. phone was off) fires right away", a[0].triggerAt >= clock.millis())
    }

    @Test fun dateOnlyUsesDefaultTimeAndGlobalSwitch() {
        val repo = TestDb.fresh()
        val ev = repo.saveEvent(EventInput("פגישה עם הזמר", "2026-10-20"), null)
        repo.setReminder(TargetType.EVENT, ev, ReminderSpec(ReminderMode.OFFSET, 0))
        assertEquals(ms("2026-10-20", "09:00"), repo.plannedAlarms().single().triggerAt)
        repo.setSetting(Repo.KEY_DEFAULT_TIME, "08:30")
        assertEquals(ms("2026-10-20", "08:30"), repo.plannedAlarms().single().triggerAt)
        repo.setSetting(Repo.KEY_REMINDERS_ENABLED, "0")
        assertTrue(repo.plannedAlarms().isEmpty())
        assertEquals(ReminderState.DISABLED, repo.reminderStatuses().single().state)
        repo.setSetting(Repo.KEY_REMINDERS_ENABLED, "1")
        repo.setEventStatus(ev, EventStatus.CANCELLED)
        assertTrue(repo.plannedAlarms().isEmpty())
        // absolute reminder
        val tr = repo.saveTransport(TransportInput(date = "2026-10-25", departTime = "17:00", departPlace = "בית", destination = "אולם"), null)
        repo.setReminder(TargetType.TRANSPORT, tr, ReminderSpec(ReminderMode.ABSOLUTE, absoluteAt = ms("2026-10-25", "15:30")))
        assertEquals(ms("2026-10-25", "15:30"), repo.plannedAlarms().single().triggerAt)
    }

    @Test fun paymentReminderStopsWhenPaid() {
        val repo = TestDb.fresh()
        val eid = repo.saveExpense(ExpenseInput("אולם", null, null, 1000000, nextPaymentDate = "2026-10-20", nextPaymentAgorot = 300000), null)
        repo.setReminder(TargetType.EXPENSE_PAYMENT, eid, ReminderSpec(ReminderMode.OFFSET, 1440))
        assertEquals(ms("2026-10-19", "09:00"), repo.plannedAlarms().single().triggerAt)
        assertEquals(1, repo.upcomingPayments().size)
        repo.addPayment(PaymentInput(eid, 1000000, "2026-10-09", null))
        assertTrue(repo.plannedAlarms().isEmpty())
        assertTrue(repo.upcomingPayments().isEmpty())
        // deleting an expense removes its reminder
        val e2 = repo.saveExpense(ExpenseInput("x", null, null, 100, nextPaymentDate = "2026-10-20"), null)
        repo.setReminder(TargetType.EXPENSE_PAYMENT, e2, ReminderSpec(ReminderMode.OFFSET, 0))
        repo.deleteExpense(e2)
        assertNull(repo.reminderFor(TargetType.EXPENSE_PAYMENT, e2))
    }

    @Test fun calendarAggregatesWithoutRetyping() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("חנות שטריימלים"), null)
        val eid = repo.saveExpense(ExpenseInput("שטריימל", null, sid, 900000, nextPaymentDate = "2026-10-14", keyDate = "2026-10-16", keyTime = "11:00", keyDateLabel = "איסוף שטריימל"), null)
        repo.saveEvent(EventInput("מדידה", "2026-10-12", "19:00", supplierId = sid), null)
        repo.saveTask(TaskInput("להביא מסמכים", dueDate = "2026-10-13"), null)
        repo.saveTransport(TransportInput(date = "2026-10-18", departTime = "16:00", departPlace = "ירושלים", destination = "בני ברק", driverName = "משה"), null)
        repo.updateWedding("חתונת לנדאו", "2026-10-18", "אולמי הנסיכה", null)
        val items = repo.calendarItems(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))
        val types = items.map { it.type.key }
        assertTrue(types.containsAll(listOf("EVENT", "TASK", "TRANSPORT", "PAYMENT_DUE", "KEY_DATE", "WEDDING")))
        val key = items.first { it.type == CalendarItemType.KEY_DATE }
        assertEquals("איסוף שטריימל", key.title); assertEquals(eid, key.id)
        assertEquals("sorted by date", items.map { it.date }.sorted(), items.map { it.date })
        assertTrue(repo.eventsForSupplier(sid).isNotEmpty())
    }

    @Test fun searchFindsEverything() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("ישראל הצלם", phone = "052-7654321", notes = "מומלץ"), null)
        repo.saveTask(TaskInput("לתאם עם ישראל"), null)
        repo.saveTransport(TransportInput(date = "2026-10-18", departPlace = "ירושלים", driverName = "ישראל"), null)
        repo.saveEvent(EventInput("פגישה", "2026-10-11", location = "משרד ישראל"), null)
        repo.addDocument(DocumentInput("חוזה ישראל", DocType.CONTRACT, "doc1.jpg", "image/jpeg", 10, supplierId = sid))
        val kinds = repo.search("ישראל").map { it.kind }.toSet()
        assertEquals(setOf("SUPPLIER", "TASK", "TRANSPORT", "EVENT", "DOCUMENT"), kinds)
        assertEquals(1, repo.search("7654321").size)
        assertEquals(0, repo.search("%").size)
        assertTrue(repo.search("  ").isEmpty())
    }

    @Test fun documentsAreSharedRecordsNotCopies() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", initialAgreedAgorot = 500000), null)
        val eid = repo.expensesForSupplier(sid).single().id
        val pid = repo.addPayment(PaymentInput(eid, 100000, "2026-10-01", null)).paymentId
        val d1 = repo.addDocument(DocumentInput("קבלה מקדמה", DocType.RECEIPT, "r1.jpg", "image/jpeg", 1234, paymentId = pid))
        val d2 = repo.addDocument(DocumentInput("חוזה", DocType.CONTRACT, "c1.pdf", "application/pdf", 999, expenseId = eid))
        assertEquals(setOf(d1, d2), repo.documentsForSupplier(sid).map { it.doc.id }.toSet())
        assertEquals(setOf(d1, d2), repo.documentsForExpense(eid).map { it.doc.id }.toSet())
        assertEquals(listOf(d1), repo.documentsFor("payment_id", pid).map { it.doc.id })
        assertEquals(2, repo.documents().size)
        assertTrue(repo.document(d1)!!.linkLabel.contains("תשלום"))
        try { repo.addDocument(DocumentInput("x", DocType.OTHER, "../evil", null, null)); fail() } catch (e: ValidationException) {}
        try { repo.addDocument(DocumentInput("x", DocType.OTHER, "ok.jpg", null, null, supplierId = 999)); fail() } catch (e: ValidationException) {}
        assertEquals("r1.jpg", repo.deleteDocument(d1))
        assertEquals(1, repo.documents().size)
    }
}

class TaskPriorityTest {
    @Test fun priorityCanChangeAfterCreationAndMovesBucket() {
        val repo = TestDb.fresh() // 2026-10-09
        val id = repo.saveTask(TaskInput("להזמין תזמורת", dueDate = "2026-11-20"), null)
        assertEquals(TaskBucket.LATER, repo.taskBuckets().entries.first { e -> e.value.any { it.id == id } }.key)
        repo.setTaskPriority(id, Priority.URGENT)
        assertEquals(Priority.URGENT, repo.task(id)!!.task.priority)
        assertTrue(repo.taskBuckets()[TaskBucket.URGENT]!!.any { it.id == id })
        repo.setTaskPriority(id, Priority.HIGH)
        assertEquals(Priority.HIGH, repo.task(id)!!.task.priority)
        assertEquals("other fields untouched", "2026-11-20", repo.task(id)!!.task.dueDate)
        try { repo.setTaskPriority(9999, Priority.HIGH); fail() } catch (e: ValidationException) {}
    }
}
