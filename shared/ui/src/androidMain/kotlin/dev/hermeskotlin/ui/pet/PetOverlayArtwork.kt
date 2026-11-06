package dev.hermeskotlin.ui.pet

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.hermeskotlin.core.pet.PetSprite
import dev.hermeskotlin.core.pet.PetState
import java.io.DataInputStream
import java.io.DataOutputStream

/** Copies the currently adopted profile pet for the overlay; the gateway remains the source of artwork. */
object PetOverlayArtwork {
    private val lock = Any()

    fun update(context: Context, sprite: PetSprite) = synchronized(lock) {
        val (row, frames) = sprite.rowFor(PetState.Idle)
        val existing = read(context)
        if (existing != null && existing.name == sprite.displayName &&
            existing.width == sprite.frameW && existing.height == sprite.frameH &&
            existing.row == row && existing.frames == frames && existing.loop == sprite.loopMs &&
            existing.bytes.contentEquals(sprite.sheet)
        ) return@synchronized
        val file = AtomicFile(context.filesDir.resolve("overlay_pet.dat"))
        val output = file.startWrite()
        try {
            val data = DataOutputStream(output.buffered())
            data.writeUTF(sprite.displayName)
            data.writeInt(sprite.frameW)
            data.writeInt(sprite.frameH)
            data.writeInt(row)
            data.writeInt(frames)
            data.writeInt(sprite.loopMs)
            data.writeInt(sprite.sheet.size)
            data.write(sprite.sheet)
            data.flush()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun read(context: Context): Artwork? = synchronized(lock) { runCatching {
        DataInputStream(AtomicFile(context.filesDir.resolve("overlay_pet.dat")).openRead().buffered()).use {
            val name = it.readUTF()
            val width = it.readInt()
            val height = it.readInt()
            val row = it.readInt()
            val frames = it.readInt()
            val loop = it.readInt()
            val length = it.readInt()
            require(width in 1..8192 && height in 1..8192 && row in 0..100 && frames in 1..100 && length in 1..20_000_000)
            val bytes = ByteArray(length)
            it.readFully(bytes)
            Artwork(name, width, height, row, frames, loop, bytes)
        }
    }.getOrNull() }
}

data class Artwork(val name: String, val width: Int, val height: Int, val row: Int, val frames: Int, val loop: Int, val bytes: ByteArray)


@androidx.compose.runtime.Composable
actual fun SyncPetOverlay(sprite: PetSprite?) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    androidx.compose.runtime.LaunchedEffect(sprite) {
        if (sprite != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                PetOverlayArtwork.update(context, sprite)
            } catch (error: Exception) {
                Log.w("PetOverlay", "Could not cache pet artwork (${error.javaClass.simpleName})")
            }
        }
    }
}
