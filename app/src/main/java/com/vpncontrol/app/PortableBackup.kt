package com.vpncontrol.app

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-protected portable backup. Password is never stored. */
object PortableBackup {
    private const val ITERATIONS = 210_000
    fun encrypt(json: String, password: CharArray): ByteArray {
        require(password.size >= 8) { "Пароль резервной копии должен содержать минимум 8 символов" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        return "VPNCBK1".toByteArray() + salt + iv + encrypted
    }
    fun decrypt(bytes: ByteArray, password: CharArray): String {
        require(bytes.size > 35 && String(bytes.copyOfRange(0, 7)) == "VPNCBK1") { "Неизвестный формат резервной копии" }
        val salt = bytes.copyOfRange(7, 23); val iv = bytes.copyOfRange(23, 35); val payload = bytes.copyOfRange(35, bytes.size)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(payload).toString(Charsets.UTF_8)
    }
}
