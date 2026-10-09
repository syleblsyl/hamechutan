package il.hamechutan.app.core

import android.content.SharedPreferences
import il.hamechutan.app.core.model.*
import il.hamechutan.app.platform.PinManager
import org.junit.Assert.*
import org.junit.Test

/** In-memory SharedPreferences for JVM tests. */
class FakePrefs : SharedPreferences {
    val map = HashMap<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(k: String, d: String?) = (map[k] as String?) ?: d
    override fun getStringSet(k: String, d: MutableSet<String>?): MutableSet<String>? = d
    override fun getInt(k: String, d: Int) = (map[k] as Int?) ?: d
    override fun getLong(k: String, d: Long) = (map[k] as Long?) ?: d
    override fun getFloat(k: String, d: Float) = (map[k] as Float?) ?: d
    override fun getBoolean(k: String, d: Boolean) = (map[k] as Boolean?) ?: d
    override fun contains(k: String) = map.containsKey(k)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        val pending = HashMap<String, Any?>(); val removed = HashSet<String>(); var clear = false
        override fun putString(k: String, v: String?) = apply { pending[k] = v }
        override fun putStringSet(k: String, v: MutableSet<String>?) = apply { pending[k] = v }
        override fun putInt(k: String, v: Int) = apply { pending[k] = v }
        override fun putLong(k: String, v: Long) = apply { pending[k] = v }
        override fun putFloat(k: String, v: Float) = apply { pending[k] = v }
        override fun putBoolean(k: String, v: Boolean) = apply { pending[k] = v }
        override fun remove(k: String) = apply { removed += k }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { if (clear) map.clear(); removed.forEach { map.remove(it) }; map.putAll(pending); return true }
        override fun apply() { commit() }
    }
}

class PinAndWipeTest {
    @Test fun pinIsHashedVerifiedAndLocksOut() {
        val prefs = FakePrefs()
        var now = 1_000_000L
        val pin = PinManager(prefs) { now }
        assertFalse(pin.isEnabled)
        assertFalse(pin.isValidPinFormat("12a4")); assertFalse(pin.isValidPinFormat("123")); assertTrue(pin.isValidPinFormat("4821"))
        val recovery = pin.setPin("4821")
        assertTrue(pin.isEnabled)
        assertEquals(4, pin.pinLength)
        assertFalse("PIN never stored in plain text", prefs.map.values.any { it.toString().contains("4821") })
        assertFalse(prefs.map.values.any { it.toString().contains(recovery.replace("-", "")) })
        assertEquals(PinManager.Result.Ok, pin.verifyPin("4821"))

        // 4 wrong attempts -> still allowed; 5th -> locked 30s
        for (i in 1..4) assertEquals(PinManager.Result.Wrong(5 - i), pin.verifyPin("0000"))
        val locked = pin.verifyPin("0000")
        assertTrue(locked is PinManager.Result.Locked)
        assertEquals(now + 30_000, (locked as PinManager.Result.Locked).untilMillis)
        assertTrue("even the right PIN is refused while locked", pin.verifyPin("4821") is PinManager.Result.Locked)
        now += 31_000
        // next wrong attempt doubles the lockout
        val l2 = pin.verifyPin("1111") as PinManager.Result.Locked
        assertEquals(now + 60_000, l2.untilMillis)
        now += 61_000
        assertEquals(PinManager.Result.Ok, pin.verifyPin("4821"))
        assertEquals("success resets counter", PinManager.Result.Wrong(4), pin.verifyPin("9999"))

        // recovery code works (case/dash-insensitive) and can reset the PIN
        assertTrue(pin.verifyRecovery("AAAA-BBBB-CCCC") is PinManager.Result.Wrong)
        assertEquals(PinManager.Result.Ok, pin.verifyRecovery(recovery.lowercase().replace("-", " ")))
        pin.changePinKeepRecovery("739164")
        assertEquals(6, pin.pinLength)
        assertTrue(pin.verifyPin("4821") is PinManager.Result.Wrong)
        assertEquals(PinManager.Result.Ok, pin.verifyPin("739164"))
        assertEquals("recovery code kept after change", PinManager.Result.Ok, pin.verifyRecovery(recovery))
        val newCode = pin.regenerateRecoveryCode()
        assertTrue(pin.verifyRecovery(recovery) is PinManager.Result.Wrong)
        assertEquals(PinManager.Result.Ok, pin.verifyRecovery(newCode))
        assertTrue(Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(newCode))
        pin.disable()
        assertFalse(pin.isEnabled)
    }

    @Test fun wipeAllResetsToEmptyWedding() {
        val repo = TestDb.fresh()
        val sid = repo.saveSupplier(SupplierInput("צלם", initialAgreedAgorot = 100000), null)
        repo.addPayment(PaymentInput(repo.expensesForSupplier(sid).single().id, 1000, "2026-10-01", null))
        repo.saveTask(TaskInput("x"), null)
        repo.updateWedding("חתונה", "2026-11-01", null, null)
        repo.wipeAll()
        assertTrue(repo.suppliers().isEmpty()); assertTrue(repo.expenses().isEmpty()); assertTrue(repo.tasks().isEmpty())
        assertEquals("החתונה שלנו", repo.wedding().title)
        assertEquals(10, repo.categories().size)
        assertEquals(6, repo.paymentMethods().size)
    }
}
