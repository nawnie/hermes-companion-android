package dev.hermeskotlin.core.resources

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.GatewayUrl
import dev.hermeskotlin.core.network.ApiResult
import dev.hermeskotlin.core.network.apiCall
import dev.hermeskotlin.core.network.errorMessage
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point-in-time snapshot from Hermes' authenticated host telemetry endpoints. Null metrics mean not reported. */
data class ComputerResources(
    val sampledAtMillis: Long,
    val cpuPercent: Double? = null,
    val logicalCpuCount: Int? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null,
    val memoryAvailableBytes: Long? = null,
    val gpuName: String? = null,
    val gpuMemoryIsShared: Boolean? = null,
    val gpuUtilPercent: Double? = null,
    val vramUsedBytes: Long? = null,
    val vramTotalBytes: Long? = null,
    val systemError: String? = null,
    val hardwareError: String? = null,
)

/** Converts a byte count to gibibytes (GiB; 1 GiB = 1024^3 bytes). */
fun Long.asGiB(): Double = toDouble() / (1024.0 * 1024.0 * 1024.0)

/** Reads the same host-side endpoints used by Hermes' System page and local-model resource planner. */
class ComputerResourcesApi(
    private val client: HttpClient,
    private val gateways: GatewayRepository,
    private val now: () -> Long = ::getTimeMillis,
) {
    /** Returns measured values when the gateway reports them; unavailable endpoints and fields stay unknown. */
    suspend fun read(): ComputerResources {
        val gateway = gateways.current()?.gatewayUrl
            ?: return ComputerResources(now(), systemError = "No gateway is selected.", hardwareError = "No gateway is selected.")

        return coroutineScope {
            val system = async { fetch<SystemStats>(gateway, "api/system/stats") }
            val hardware = async { fetch<HardwareStats>(gateway, "api/local-models/hardware") }
            val (systemStats, systemError) = system.await()
            val (hardwareStats, hardwareError) = hardware.await()
            ComputerResources(
                sampledAtMillis = now(),
                cpuPercent = systemStats?.cpuPercent,
                logicalCpuCount = systemStats?.cpuCount,
                memoryUsedBytes = systemStats?.memory?.used
                    ?: hardwareStats?.let { stats ->
                        if (stats.ramTotalBytes != null && stats.ramAvailableBytes != null) {
                            (stats.ramTotalBytes - stats.ramAvailableBytes).coerceAtLeast(0)
                        } else null
                    },
                memoryTotalBytes = systemStats?.memory?.total ?: hardwareStats?.ramTotalBytes,
                memoryAvailableBytes = systemStats?.memory?.available ?: hardwareStats?.ramAvailableBytes,
                gpuName = hardwareStats?.gpuName,
                gpuMemoryIsShared = hardwareStats?.uma,
                gpuUtilPercent = hardwareStats?.gpuUtilPercent,
                vramUsedBytes = hardwareStats?.vramUsedBytes,
                vramTotalBytes = hardwareStats?.vramTotalBytes,
                systemError = systemError,
                hardwareError = hardwareError,
            )
        }
    }

    private suspend inline fun <reified T> fetch(gateway: GatewayUrl, path: String): Pair<T?, String?> =
        when (val response = apiCall { client.get(gateway.resolve(path)) }) {
            is ApiResult.Success -> try {
                response.value.body<T>() to null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                null to (error.message?.takeIf(String::isNotBlank) ?: "Telemetry response could not be read.")
            }
            else -> null to (response.errorMessage ?: "Telemetry unavailable.")
        }
}

@Serializable
private data class SystemStats(
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    @SerialName("cpu_count") val cpuCount: Int? = null,
    val memory: SystemMemory? = null,
)

@Serializable
private data class SystemMemory(
    val total: Long? = null,
    val available: Long? = null,
    val used: Long? = null,
)

@Serializable
private data class HardwareStats(
    val uma: Boolean? = null,
    @SerialName("vram_total_bytes") val vramTotalBytes: Long? = null,
    @SerialName("ram_total_bytes") val ramTotalBytes: Long? = null,
    @SerialName("ram_available_bytes") val ramAvailableBytes: Long? = null,
    @SerialName("gpu_name") val gpuName: String? = null,
    @SerialName("gpu_util_percent") val gpuUtilPercent: Double? = null,
    @SerialName("vram_used_bytes") val vramUsedBytes: Long? = null,
)
