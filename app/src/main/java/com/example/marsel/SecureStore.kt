package com.example.marsel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Chantier confidentialité (TODO.md §1) : authentification locale réelle et
 * chiffrement au repos des données sensibles. Principe absolu : aucun
 * secret ne quitte jamais l'appareil — pas de compte serveur, pas de
 * connexion Google/Facebook/Apple (exclue explicitement par l'utilisateur).
 *
 * - Le mot de passe/code local n'est JAMAIS stocké en clair : il est haché
 *   avec PBKDF2WithHmacSHA256 (120 000 itérations, sel aléatoire par
 *   appareil) via setLocalSecret()/verifyLocalSecret(). Le hash n'est pas
 *   réversible, il ne peut être utilisé que pour vérifier une saisie.
 * - Les données sensibles (marsel_user, marsel_contacts, marsel_profile,
 *   marsel_emergency) transitent par secureStore()/secureRetrieve() :
 *   chiffrement AES/256-GCM avec une clé générée et gardée dans l'Android
 *   Keystore, non exportable, jamais chargée en mémoire applicative en
 *   clair et jamais transmise nulle part.
 */
class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val authPrefs = appContext.getSharedPreferences("marsel_auth", Context.MODE_PRIVATE)
    private val dataPrefs = appContext.getSharedPreferences("marsel_secure_data", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SecureStore"
        private const val KEY_ALIAS = "marsel_secure_data_key"
        // QA-FIX : clé DISTINCTE, dédiée à la porte biométrique — ne sert
        // jamais à chiffrer de données, uniquement à exiger une preuve
        // biométrique cryptographique (voir getOrCreateBiometricGateKey).
        private const val BIOMETRIC_KEY_ALIAS = "marsel_biometric_gate_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH = 256
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    // -------------------------------------------------------------------
    // Authentification locale (PIN/mot de passe haché — TODO.md §1.1/1.3)
    // -------------------------------------------------------------------

    fun hasLocalAuth(): Boolean = authPrefs.contains("hash")

    fun setLocalSecret(secret: String): Boolean {
        if (secret.isBlank()) return false
        return try {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val hash = pbkdf2(secret, salt)
            authPrefs.edit()
                .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "setLocalSecret failed: ${e.message}")
            false
        }
    }

    fun verifyLocalSecret(secret: String): Boolean {
        val saltB64 = authPrefs.getString("salt", null) ?: return false
        val hashB64 = authPrefs.getString("hash", null) ?: return false
        return try {
            val salt = Base64.decode(saltB64, Base64.NO_WRAP)
            val expected = Base64.decode(hashB64, Base64.NO_WRAP)
            val actual = pbkdf2(secret, salt)
            constantTimeEquals(expected, actual)
        } catch (e: Exception) {
            Log.e(TAG, "verifyLocalSecret failed: ${e.message}")
            false
        }
    }

    private fun pbkdf2(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    // Comparaison à temps constant : évite de fuiter, via le temps de
    // réponse, la longueur du préfixe correct d'un mot de passe candidat.
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].toInt() xor b[i].toInt())
        return result == 0
    }

    // -------------------------------------------------------------------
    // Chiffrement au repos (AES/256-GCM, clé Android Keystore — §1.4)
    // -------------------------------------------------------------------

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    // QA-FIX (§1.2) : BiometricPrompt sans CryptoObject lié ne vérifie que
    // « un moyen biométrique fort est enrôlé sur ce téléphone » — n'importe
    // quelle empreinte/visage du système déverrouille alors l'app, pas
    // seulement celle du propriétaire. En lisant cette clé à travers un
    // Cipher exigé par le prompt, ET en la configurant pour s'auto-invalider
    // dès qu'un NOUVEAU moyen biométrique est enrôlé
    // (setInvalidatedByBiometricEnrollment), un empreinte ajoutée après coup
    // (ex. par quelqu'un ayant eu un accès bref aux réglages du téléphone)
    // rend cette clé définitivement inutilisable → la biométrie échoue et
    // retombe sur le mot de passe, au lieu de déverrouiller silencieusement.
    fun getOrCreateBiometricGateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    /** Efface la clé-porte (ex. après KeyPermanentlyInvalidatedException) —
     *  elle sera régénérée transparemment au prochain essai biométrique. */
    fun resetBiometricGateKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
        } catch (e: Exception) {
            Log.e(TAG, "resetBiometricGateKey failed: ${e.message}")
        }
    }

    fun secureStore(key: String, value: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            dataPrefs.edit().putString(key, payload).apply()
        } catch (e: Exception) {
            Log.e(TAG, "secureStore($key) failed: ${e.message}")
        }
    }

    fun secureRetrieve(key: String): String? {
        val payload = dataPrefs.getString(key, null) ?: return null
        return try {
            val parts = payload.split(":", limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "secureRetrieve($key) failed: ${e.message}")
            null
        }
    }

    fun secureRemove(key: String) {
        dataPrefs.edit().remove(key).apply()
    }
}
