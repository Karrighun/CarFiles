package dev.carfiles

object AudioSearch {
    fun matches(name: String, query: String): Boolean {
        val normalized = name.lowercase()
        return query.trim().lowercase().split(Regex("\\s+")).all { it in normalized }
    }
}
