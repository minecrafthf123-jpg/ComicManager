package com.comicmanager.util

/** Extracts a JM album id only from filename shapes that clearly carry an id. */
object JmIdParser {
    private val numericBackupName = Regex("""^\s*([1-9]\d{0,9})(?:\s*\(\d+\))?\s*$""")
    private val explicitJmPrefix = Regex(
        """(?i)^\s*[\[【(（]?\s*JM[\s_#：:\-]*([1-9]\d{0,9})(?!\d)"""
    )
    private val bracketedNumber = Regex(
        """^\s*[\[【(（]\s*([1-9]\d{0,9})\s*[\]】)）]"""
    )
    private val leadingLongNumber = Regex("""^\s*([1-9]\d{4,9})(?=$|[\s_-])""")

    fun extract(fileNameOrStem: String): String? {
        val stem = fileNameOrStem
            .trim()
            .replace(Regex("""(?i)\.zip$"""), "")
            .trim()

        val rawId = numericBackupName.matchEntire(stem)?.groupValues?.get(1)
            ?: explicitJmPrefix.find(stem)?.groupValues?.get(1)
            ?: bracketedNumber.find(stem)?.groupValues?.get(1)
            ?: leadingLongNumber.find(stem)?.groupValues?.get(1)

        return normalize(rawId)
    }

    fun normalize(value: String?): String? = value
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0 }
        ?.toString()
}
