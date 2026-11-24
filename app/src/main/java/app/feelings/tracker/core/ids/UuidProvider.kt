package app.feelings.tracker.core.ids

import java.util.UUID

fun interface UuidProvider {
    fun newId(): String

    companion object {
        fun random(): UuidProvider = UuidProvider { UUID.randomUUID().toString() }
    }
}
