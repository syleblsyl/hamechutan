package il.hamechutan.app.platform

import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App lock with a PIN. The PIN is never stored: only a salted PBKDF2-HMAC-SHA256 hash.
 * A one-time recovery code (also stored only as a hash) lets the user reset a forgotten PIN
 * without losing data. Wrong attempts trigger an escalating lockout that survives restarts.
 */
class PinManager(private val sp: SharedPreferences, private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val rnd = SecureRandom()

    companion object {
        const val ITERATIONS = 60_000
        const val FREE_ATTEMPTS = 5
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }

    val isEnabled: Boolean get() = sp.contains("pin_hash")

    sealed class Result {
        object Ok : Result()
        data class Wrong(val attemptsBeforeLock: Int) : Result()
        data class Locked(val untilMillis: Long) : Result()
    }

    private fun hash(secret: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = java.util.Base64.getDecoder().decode(s)

    fun isValidPinFormat(pin: String) = pin.length in 4..8 && pin.all { it.isDigit() }

    /** Sets a new PIN and returns a freshly generated recovery code to show to the user once. */
    fun setPin(pin: String): String {
        require(isValidPinFormat(pin))
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val code = newRecoveryCode()
        val rsalt = ByteArray(16).also { rnd.nextBytes(it) }
        sp.edit()
            .putString("pin_hash", b64(hash(pin, salt, ITERATIONS)))
            .putString("pin_salt", b64(salt))
            .putInt("pin_iter", ITERATIONS)
            .putInt("pin_len", pin.length)
            .putString("rec_hash", b64(hash(normalizeCode(code), rsalt, ITERATIONS)))
            .putString("rec_salt", b64(rsalt))
            .putInt("fails", 0).putLong("lock_until", 0)
            .commit()
        return code
    }

    fun changePinKeepRecovery(pin: String) {
        require(isValidPinFormat(pin))
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        sp.edit().putString("pin_hash", b64(hash(pin, salt, ITERATIONS))).putString("pin_salt", b64(salt))
            .putInt("pin_iter", ITERATIONS).putInt("pin_len", pin.length).putInt("fails", 0).putLong("lock_until", 0).commit()
    }

    fun regenerateRecoveryCode(): String {
        val code = newRecoveryCode()
        val rsalt = ByteArray(16).also { rnd.nextBytes(it) }
        sp.edit().putString("rec_hash", b64(hash(normalizeCode(code), rsalt, ITERATIONS))).putString("rec_salt", b64(rsalt)).commit()
        return code
    }

    val pinLength: Int get() = sp.getInt("pin_len", 4)

    fun disable() {
        sp.edit().remove("pin_hash").remove("pin_salt").remove("pin_iter").remove("pin_len").remove("rec_hash").remove("rec_salt")
            .putInt("fails", 0).putLong("lock_until", 0).commit()
    }

    fun lockedUntil(): Long = sp.getLong("lock_until", 0).takeIf { it > clock() } ?: 0

    private fun check(secret: String, hashKey: String, saltKey: String): Result {
        val until = lockedUntil()
        if (until > 0) return Result.Locked(until)
        val stored = sp.getString(hashKey, null) ?: return Result.Wrong(0)
        val salt = unb64(sp.getString(saltKey, "")!!)
        val ok = MessageDigest.isEqual(unb64(stored), hash(secret, salt, sp.getInt("pin_iter", ITERATIONS)))
        if (ok) {
            sp.edit().putInt("fails", 0).putLong("lock_until", 0).commit()
            return Result.Ok
        }
        val fails = sp.getInt("fails", 0) + 1
        val ed = sp.edit().putInt("fails", fails)
        return if (fails >= FREE_ATTEMPTS) {
            // 30s, 60s, 2m, 4m ... up to 15 minutes
            val secs = (30L shl (fails - FREE_ATTEMPTS).coerceAtMost(5)).coerceAtMost(900)
            val lockUntil = clock() + secs * 1000
            ed.putLong("lock_until", lockUntil).commit()
            Result.Locked(lockUntil)
        } else {
            ed.commit()
            Result.Wrong(FREE_ATTEMPTS - fails)
        }
    }

    fun verifyPin(pin: String): Result = check(pin, "pin_hash", "pin_salt")
    fun verifyRecovery(code: String): Result = check(normalizeCode(code), "rec_hash", "rec_salt")

    private fun normalizeCode(c: String) = c.uppercase().filter { it.isLetterOrDigit() }

    private fun newRecoveryCode(): String {
        val sb = StringBuilder()
        repeat(12) { i ->
            if (i > 0 && i % 4 == 0) sb.append('-')
            sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        }
        return sb.toString()
    }
}
