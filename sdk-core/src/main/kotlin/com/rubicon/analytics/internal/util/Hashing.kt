package com.rubicon.analytics.internal.util

import com.rubicon.analytics.RubiconInternal
import java.security.MessageDigest

@RubiconInternal
public object Hashing {
    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * The SHA-256 of [text] in UTF-8, as 64 lowercase hex characters. The event directory uses the
     * first 16 of the API key's hash (Disk 2c).
     */
    public fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = CharArray(digest.size * 2)
        digest.forEachIndexed { i, byte ->
            out[i * 2] = HEX[(byte.toInt() ushr 4) and 0xF]
            out[i * 2 + 1] = HEX[byte.toInt() and 0xF]
        }
        return String(out)
    }
}
