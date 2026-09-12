package com.gits.compositioncompass.Queries

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

//Pandora's JSON-RPC API encrypts request bodies (and the syncTime field returned
//by auth.partnerLogin) using plain Blowfish/ECB with two distinct keys:
//  - "outKey" (a.k.a. Pandora's ENCRYPTION_KEY) is used to encrypt outgoing request bodies
//  - "inKey"  (a.k.a. Pandora's DECRYPTION_KEY) is used to decrypt the syncTime blob
//This mirrors pydora's pandora.transport.Encryptor 1:1.
class PandoraCryptor(private val inKey: String, private val outKey: String) {

    //encrypts a JSON request body -> lowercase hex string, PKCS5/PKCS7-style padding
    //(Java's "PKCS5Padding" on an 8-byte block cipher behaves as PKCS7 padding, which
    //is what Pandora's own padding scheme amounts to)
    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("Blowfish/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(outKey.toByteArray(Charsets.US_ASCII), "Blowfish"))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return encrypted.toHex().lowercase()
    }

    //decrypts the hex-encoded "syncTime" field returned by auth.partnerLogin.
    //The decrypted payload is NOT padded (no stripping needed) and wraps the
    //ASCII timestamp with a 4-byte prefix and a 2-byte suffix.
    fun decryptSyncTime(data: String): Long {
        val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(inKey.toByteArray(Charsets.US_ASCII), "Blowfish"))
        val decrypted = cipher.doFinal(data.uppercase().hexToBytes())
        val timeBytes = decrypted.copyOfRange(4, decrypted.size - 2)
        return String(timeBytes, Charsets.US_ASCII).toLong()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val len = length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) +
                    Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }
}
