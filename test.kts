val filename = "Peddi (2026) UNCUT 1080p 10bit DS4K NF WEBRip x265 HEVC [CDB.mkv"
val mime = "application/octet-stream"

val ext = filename.substringAfterLast('.', "").lowercase().trim()
val filenameLower = filename.lowercase()

val hasVideoExt = ext in listOf("mkv", "mp4", "avi", "mov", "flv", "wmv", "webm", "m4v", "3gp", "ts", "m2ts", "vob")
val hasVideoMime = mime.startsWith("video/") || mime.contains("matroska")
val hasVideoKeywords = listOf("mkv", "mp4", "1080p", "720p", "480p", "4k", "hevc", "x265", "x264", "web-dl", "webrip", "bluray").any { filenameLower.contains(it) }

val isVideo = hasVideoExt || hasVideoMime || hasVideoKeywords

println("ext: '$ext'")
println("hasVideoExt: $hasVideoExt")
println("hasVideoMime: $hasVideoMime")
println("hasVideoKeywords: $hasVideoKeywords")
println("isVideo: $isVideo")
