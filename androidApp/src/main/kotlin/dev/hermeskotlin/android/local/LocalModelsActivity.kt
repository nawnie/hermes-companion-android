package dev.hermeskotlin.android.local

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.app.ActivityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

private data class Model(val title: String, val file: String, val url: String, val size: Long, val sha256: String)

private val models = listOf(
    Model("Google Gemma 4 E2B · 2.59 GB", "gemma4-e2b.litertlm",
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/3d48132a00cf76de57d926de30f5c5c5ed9b8c0d/gemma-4-E2B-it.litertlm",
        2588147712L, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"),
    Model("Qwen 3 0.6B · 614 MB", "qwen3-06b.litertlm",
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/7c650468cc1598861b1a0deb0ab25be6b20cab13/Qwen3-0.6B.litertlm",
        613722264L, "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108"),
    Model("Qwen 3 0.6B compact · 343 MB", "qwen3-06b-compact.litertlm",
        "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/7c650468cc1598861b1a0deb0ab25be6b20cab13/Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm",
        343490560L, "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"),
)

private object LocalEngineOwner {
    val mutex = Mutex()
    var owner: Any? = null
    var engine: Engine? = null
    var conversation: Conversation? = null

    fun close() {
        val oldConversation = conversation
        val oldEngine = engine
        owner = null; conversation = null; engine = null
        try { oldConversation?.close() } finally { oldEngine?.close() }
    }
}

/** On-device chat is separate from the remote agent and has no access to its tools or sessions. */
@OptIn(ExperimentalApi::class)
class LocalModelsActivity : ComponentActivity() {
    private val ownerToken = Any()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var busy by mutableStateOf(false)
    private var status by mutableStateOf("Choose a model to download, then load it on this phone.")
    private var speed by mutableStateOf("")
    private var active by mutableStateOf<String?>(null)
    private var transcript by mutableStateOf("")
    private var prompt by mutableStateOf("")
    private var generationJob: Job? = null
    private val toolCancelled = AtomicBoolean(false)
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                getSharedPreferences("local_folder", MODE_PRIVATE).edit().putString("tree", uri.toString()).apply()
                status = "Selected folder available to local file tools."
            } catch (e: Exception) { status = "Could not retain folder access: ${e.message}" }
        }
    }
    private val imagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "Image library access granted." else "Image access denied; local image tools cannot see the full library."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xff5289ff), background = Color(0xff0b0e13))) {
                Surface(color = Color(0xff0b0e13), contentColor = Color(0xffdce2eb)) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("HERMES AGENT", fontFamily = FontFamily.Serif, fontSize = 30.sp)
                    Text("On this phone", style = MaterialTheme.typography.titleLarge)
                    Text("Local model chat runs on your phone. Hermes gateway tools and sessions are separate. Keep this screen open during a download.")
                    models.forEach { model ->
                        val available = File(filesDir, model.file).isFile
                        Text(model.title, style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { download(model) }, enabled = !busy) { Text(if (available) "Download again" else "Download") }
                            Button(onClick = { load(model) }, enabled = !busy && available) { Text("Load") }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        Button(onClick = { requestImageAccess() }, enabled = !busy) { Text("Allow image library access") }
                    }
                    Button(onClick = { folderPicker.launch(null) }, enabled = !busy) { Text("Choose file folder") }
                    Text(status)
                    if (speed.isNotEmpty()) Text(speed)
                    if (active != null) {
                        Text("Loaded: $active")
                        if (transcript.isNotEmpty()) Text(transcript)
                        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { send() }, enabled = !busy && prompt.isNotBlank()) { Text("Send") }
                        if (busy && generationJob != null) Button(onClick = { toolCancelled.set(true); status = "Stopping image operation; waiting for the model response..." }) { Text("Stop image operation") }
                        Button(onClick = { unload() }, enabled = !busy) { Text("Unload model") }
                    }
                }
                }
            }
        }
    }

    private fun requestImageAccess() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) status = "Image library access is already granted."
        else imagePermission.launch(permission)
    }

    private fun download(model: Model) {
        busy = true
        status = "Downloading ${model.title}…"
        lifecycleScope.launch {
            val partial = File(filesDir, model.file + ".part")
            try {
                withContext(Dispatchers.IO) {
                    require(filesDir.usableSpace > model.size + 256_000_000L) { "Not enough free storage" }
                    val target = File(filesDir, model.file)
                    val connection = URL(model.url).openConnection()
                    connection.connectTimeout = 30_000
                    connection.readTimeout = 30_000
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    var shown = -1
                    connection.getInputStream().use { input ->
                        partial.outputStream().buffered().use { output ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= model.size) { "Unexpected download size" }
                                val percent = (count * 100 / model.size).toInt()
                                if (percent != shown) {
                                    shown = percent
                                    withContext(Dispatchers.Main) { status = "Downloading ${model.title}: $percent%" }
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(count == model.size && hash == model.sha256) { "Download verification failed" }
                    require(partial.renameTo(target)) { "Could not save the verified model" }
                }
                status = "Download verified. Tap Load to use it."
            } catch (e: CancellationException) { partial.delete(); throw e }
            catch (e: Exception) { partial.delete(); status = "Download stopped: ${e.message ?: "unknown error"}" }
            finally { busy = false }
        }
    }

    private fun load(model: Model) {
        speed = ""
        val memory = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        if (model.file.startsWith("gemma") && memory.totalMem < 8_000_000_000L) {
            status = "Gemma 4 E2B needs a phone with at least 8 GB of memory."
            return
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) imagePermission.launch(permission)
        }
        busy = true
        status = "Loading ${model.title}…"
        lifecycleScope.launch {
            try {
                val backendUsed = withContext(Dispatchers.IO) {
                    LocalEngineOwner.mutex.withLock {
                        LocalEngineOwner.close()
                        val previousBenchmark = ExperimentalFlags.enableBenchmark
                        ExperimentalFlags.enableBenchmark = true
                        // Google documents GPU support for these two model files. The compact
                        // dynamic-quantized variant remains on CPU until validated separately.
                        val gpuCandidate = Build.MODEL.equals("SM-S938U", ignoreCase = true) &&
                            model.file in setOf("gemma4-e2b.litertlm", "qwen3-06b.litertlm")
                        var backendUsed = "CPU"
                        val next = try {
                            fun initialize(backend: Backend): Engine {
                                val candidate = Engine(EngineConfig(
                                    modelPath = File(filesDir, model.file).absolutePath,
                                    backend = backend,
                                    maxNumTokens = 4096,
                                    cacheDir = cacheDir.absolutePath,
                                ))
                                try { candidate.initialize(); return candidate }
                                catch (e: Throwable) { if (candidate.isInitialized()) runCatching { candidate.close() }; throw e }
                            }
                            if (gpuCandidate) {
                                try { initialize(Backend.GPU()).also { backendUsed = "GPU" } }
                                catch (e: Exception) {
                                    android.util.Log.w("LocalModels", "GPU initialization failed; falling back to CPU", e)
                                    initialize(Backend.CPU())
                                } catch (e: LinkageError) {
                                    android.util.Log.w("LocalModels", "GPU libraries unavailable; falling back to CPU", e)
                                    initialize(Backend.CPU())
                                }
                            } else initialize(Backend.CPU())
                        } finally { ExperimentalFlags.enableBenchmark = previousBenchmark }
                        try {
                            coroutineContext.ensureActive()
                            LocalEngineOwner.conversation = next.createConversation(ConversationConfig(
                                systemInstruction = Contents.of("You are a phone-local assistant. Use the provided image tools when the user asks to inspect or collect phone images. Report the actual tool result and any failures or limitations; never claim an action occurred without a tool result."),
                                // Keep the small CPU model responsive; leave Gemma and full Qwen unchanged.
                                extraContext = if (model.file == "qwen3-06b-compact.litertlm") mapOf("enable_thinking" to false) else emptyMap(),
                                thinkingConfig = if (model.file == "qwen3-06b-compact.litertlm") ThinkingConfig(enableThinking = false) else null,
                                tools = listOf(tool(LocalImageTools(applicationContext, toolCancelled) { update ->
                                    runOnUiThread { status = update }
                                }), tool(LocalFolderTools(applicationContext)))
                            ))
                            LocalEngineOwner.engine = next
                            LocalEngineOwner.owner = ownerToken
                            backendUsed
                        } catch (e: Exception) { next.close(); throw e }
                    }
                }
                active = model.title
                transcript = ""
                status = "Ready on this phone ($backendUsed)."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { active = null; status = "Couldn't load model: ${e.message ?: "unknown error"}" }
            finally { busy = false }
        }
    }

    private fun send() {
        val text = prompt.trim()
        if (text.isBlank()) return
        toolCancelled.set(false)
        speed = ""
        prompt = ""; transcript += "\nYou: $text\nHermes: "; busy = true
        status = "Generating a response on this phone…"
        generationJob = lifecycleScope.launch {
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val (response, metric) = withContext(Dispatchers.IO) { LocalEngineOwner.mutex.withLock {
                    check(LocalEngineOwner.owner === ownerToken) { "Model was unloaded" }
                    val conversation = requireNotNull(LocalEngineOwner.conversation)
                    val reply = conversation.sendMessage(text, maxOutputToken = 1024).toString()
                    val benchmark = runCatching { conversation.getBenchmarkInfo() }.getOrNull()
                    reply to benchmark
                } }
                val seconds = (android.os.SystemClock.elapsedRealtime() - started) / 1000.0
                transcript += response
                status = "Response complete."
                speed = if (metric != null && metric.lastDecodeTokenCount > 0 && metric.lastDecodeTokensPerSecond.isFinite() && metric.lastDecodeTokensPerSecond > 0)
                    "Last decode: %.1f tokens/s (%d tokens); total request: %.1f s.".format(metric.lastDecodeTokensPerSecond, metric.lastDecodeTokenCount, seconds)
                else "Response completed in %.1f s; token speed unavailable.".format(seconds)
            }
            catch (e: CancellationException) { status = "Response stopped."; transcript += "\nResponse stopped."; throw e }
            catch (e: Exception) { status = "Response failed."; transcript += "\nCouldn't complete: ${e.message ?: "unknown error"}" }
            finally { busy = false; generationJob = null }
        }
    }

    private fun unload() {
        speed = ""
        busy = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { LocalEngineOwner.mutex.withLock {
                if (LocalEngineOwner.owner === ownerToken) LocalEngineOwner.close()
            } }; status = "Model unloaded." }
            catch (e: Exception) { status = "Model unload failed: ${e.message ?: "unknown error"}" }
            finally { active = null; busy = false }
        }
    }

    override fun onDestroy() {
        toolCancelled.set(true)
        super.onDestroy()
        cleanupScope.launch { LocalEngineOwner.mutex.withLock {
            if (LocalEngineOwner.owner === ownerToken) runCatching { LocalEngineOwner.close() }
        } }
    }
}
