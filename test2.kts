fun parseRange(header: String?): Pair<Long?, Long?> {
    if (header == null) return Pair(null, null)
    return try {
        val range = header.removePrefix("bytes=")
        val parts = range.split("-")
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        Pair(start, end)
    } catch (e: Exception) {
        Pair(null, null)
    }
}

println(parseRange("bytes=-500"))
println(parseRange("bytes=500-"))
println(parseRange("bytes=500-1000"))
println(parseRange(null))
