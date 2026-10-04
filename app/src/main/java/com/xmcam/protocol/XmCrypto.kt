package com.xmcam.protocol

import java.security.MessageDigest

/** Hash de contraseña propio de XM. */
object XmCrypto {
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

    /**
     * Algoritmo: MD5 (16 bytes) -> se suman los bytes por parejas (8 sumas) ->
     * cada suma módulo 62 -> carácter del alfabeto 0-9, A-Z, a-z.
     * Ejemplo verificado: contraseña vacía -> "tlJwpbo6".
     */
    fun hashPassword(password: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(8)
        var i = 0
        while (i < 16) {
            val sum = (md5[i].toInt() and 0xFF) + (md5[i + 1].toInt() and 0xFF)
            sb.append(ALPHABET[sum % 62])
            i += 2
        }
        return sb.toString()
    }
}
