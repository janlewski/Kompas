package app.kompas.core.time

import java.time.ZoneId

fun interface ZoneProvider {
    fun zoneId(): ZoneId

    companion object {
        fun system(): ZoneProvider = ZoneProvider { ZoneId.systemDefault() }
    }
}
