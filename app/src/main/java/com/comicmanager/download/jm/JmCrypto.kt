package com.comicmanager.download.jm

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JmCrypto {
    const val APP_TOKEN_SECRET = "185Hcomic3PAPP7R"
    const val APP_DATA_SECRET = "185Hcomic3PAPP7R"
    const val APP_VERSION = "2.1.2"

    val API_DOMAINS = listOf(
        "https://www.cdnhjk.net",
        "https://www.cdngwc.cc",
        "https://www.cdngwc.net",
        "https://www.cdngwc.club"
    )

    val IMAGE_DOMAINS = listOf(
        "https://cdn-msp.jmapiproxy1.cc",
        "https://cdn-msp.jmapiproxy2.cc",
        "https://cdn-msp2.jmapiproxy2.cc",
        "https://cdn-msp3.jmapiproxy2.cc",
        "https://cdn-msp.jmapinodeudzn.net",
        "https://cdn-msp3.jmapinodeudzn.net"
    )

    fun md5hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun tokenAndParam(ts: Long): Pair<String, String> {
        val tokenparam = "$ts,$APP_VERSION"
        val token = md5hex("$ts$APP_TOKEN_SECRET")
        return Pair(token, tokenparam)
    }

    fun decryptRespData(data: String, ts: Long): String {
        val secret = APP_DATA_SECRET
        val keyStr = md5hex("$ts$secret")
        val key = keyStr.toByteArray(Charsets.UTF_8)

        val dataB64 = Base64.decode(data, Base64.DEFAULT)

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val decrypted = cipher.doFinal(dataB64)

        val padding = decrypted.last().toInt()
        val unpadded = if (padding in 1..16) decrypted.copyOfRange(0, decrypted.size - padding) else decrypted

        return String(unpadded, Charsets.UTF_8)
    }
}
