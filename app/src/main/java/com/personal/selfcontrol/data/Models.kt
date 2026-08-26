package com.personal.selfcontrol.data

data class ForceCloseRecord(val packageName: String, val timestamp: Long) {
    fun toStorageString() = "$packageName|$timestamp"

    companion object {
        fun from(s: String): ForceCloseRecord? {
            val parts = s.split("|")
            if (parts.size != 2) return null
            return ForceCloseRecord(parts[0], parts[1].toLongOrNull() ?: return null)
        }
    }
}
