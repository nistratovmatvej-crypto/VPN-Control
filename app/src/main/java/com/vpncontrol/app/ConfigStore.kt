package com.vpncontrol.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts imported VPN configuration with a non-exportable Android Keystore AES key. */
object ConfigStore {
    private const val ALIAS = "vpncontrol_config_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain); val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + encrypted
    }
    fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.isNotEmpty()) { "Пустая конфигурация" }
        val ivSize = encrypted[0].toInt() and 0xff
        require(ivSize in 12..16 && encrypted.size > ivSize + 1) { "Повреждённый зашифрованный профиль" }
        val iv = encrypted.copyOfRange(1, 1 + ivSize); val data = encrypted.copyOfRange(1 + ivSize, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(data)
    }
}
