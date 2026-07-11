fun main() {
    val raw = "-1001, -1002\n-1003;-1004"
    val list = raw.split(",", " ", "\n", "\r", ";").map { it.trim() }.filter { it.isNotEmpty() }
    println(list)
}
