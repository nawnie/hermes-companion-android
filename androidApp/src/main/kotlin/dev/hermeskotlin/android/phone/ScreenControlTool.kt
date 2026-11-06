package dev.hermeskotlin.android.phone

import dev.hermeskotlin.core.phone.PhoneToolException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Runs requests while the phone's screen-control switch and accessibility service are enabled. */
internal class ScreenControlTool {
    suspend fun run(args: JsonObject): JsonObject {
        val action = args.string("action") ?: throw PhoneToolException("action is required.")
        if (action !in setOf("inspect", "tap", "swipe", "back", "home")) throw PhoneToolException("Unknown screen action.")
        fun coordinate(name: String): Int = (args.int(name) ?: throw PhoneToolException("$name is required."))
            .also { if (it !in 0..10000) throw PhoneToolException("$name must be 0 to 10000.") }
        val x = if (action == "tap") coordinate("x") else if (action == "swipe") coordinate("start_x") else 0
        val y = if (action == "tap") coordinate("y") else if (action == "swipe") coordinate("start_y") else 0
        val endX = if (action == "swipe") coordinate("end_x") else 0
        val endY = if (action == "swipe") coordinate("end_y") else 0
        val duration = if (action == "swipe") (args.int("duration_ms") ?: 400).also {
            if (it !in 100..2000) throw PhoneToolException("duration_ms must be 100 to 2000.")
        } else 80
        val service = ScreenControlService.instance ?: throw PhoneToolException("Enable Hermes Companion screen control in Android accessibility settings.")
        return if (action == "inspect") {
            val (jpeg, width, height) = service.screenshot()
            buildJsonObject {
                put("width", width); put("height", height)
                put("screenshot", buildJsonObject { put("mime_type", "image/jpeg"); put("base64", jpeg) })
            }
        } else {
            service.gesture(action, x, y, endX, endY, duration)
            buildJsonObject { put("ok", true); put("action", action) }
        }
    }
}
