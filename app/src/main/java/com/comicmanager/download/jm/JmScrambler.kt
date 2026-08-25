package com.comicmanager.download.jm

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.security.MessageDigest

object JmScrambler {

    private const val SCRAMBLE_268850 = 268850
    private const val SCRAMBLE_421926 = 421926

    fun calcNum(aid: Long, filename: String): Int {
        if (aid < SCRAMBLE_268850) return 10
        val x = if (aid < SCRAMBLE_421926) 10 else 8
        val s = "$aid$filename"
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val lastChar = hex.last()
        var num = Character.getNumericValue(lastChar)
        num %= x
        return num * 2 + 2
    }

    fun descramble(src: Bitmap, num: Int): Bitmap {
        if (num <= 0) return src
        val w = src.width
        val h = src.height

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)

        val over = h % num
        val baseMove = h / num

        for (i in 0 until num) {
            var move = baseMove
            var ySrc = h - (move * (i + 1)) - over
            var yDst = move * i

            if (i == 0) {
                move += over
            } else {
                yDst += over
            }

            if (ySrc < 0 || move <= 0 || ySrc + move > h) continue

            val strip = Bitmap.createBitmap(src, 0, ySrc, w, move)
            canvas.drawBitmap(strip, 0f, yDst.toFloat(), null)
            if (strip != src) strip.recycle()
        }
        return result
    }
}
