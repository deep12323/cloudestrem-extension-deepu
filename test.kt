package test
import com.lagradost.cloudstream3.MainAPI

fun test(api: MainAPI) {
    println(api.javaClass.declaredFields.joinToString("\n") { it.name })
}
