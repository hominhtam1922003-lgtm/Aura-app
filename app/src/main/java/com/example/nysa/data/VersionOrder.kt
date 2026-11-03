package com.example.nysa.data

/** The user's "Ưu tiên bản dịch" setting as a langKey fallback order (TM → VS → LT by default). */
fun versionPriorityOrder(priority: String): List<String> = when (priority) {
    "VS" -> listOf("vs", "tm", "lt")
    "LT" -> listOf("lt", "tm", "vs")
    else -> listOf("tm", "vs", "lt")
}
