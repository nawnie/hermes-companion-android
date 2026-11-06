package dev.hermeskotlin.android.overlay

import android.content.Context

internal class OverlayPrefs(context: Context) {
    private val p = context.getSharedPreferences("pet_overlay", Context.MODE_PRIVATE)
    var opacity: Float
        get() = p.getFloat("opacity", .75f).coerceIn(.2f, 1f)
        set(v) { p.edit().putFloat("opacity", v.coerceIn(.2f, 1f)).apply() }
    var heightDp: Int
        get() = p.getInt("height_dp", 150).coerceIn(72, 280)
        set(v) { p.edit().putInt("height_dp", v.coerceIn(72, 280)).apply() }
    var position: Float
        get() = p.getFloat("position", .85f).coerceIn(0f, 1f)
        set(v) { p.edit().putFloat("position", v.coerceIn(0f, 1f)).apply() }
}

