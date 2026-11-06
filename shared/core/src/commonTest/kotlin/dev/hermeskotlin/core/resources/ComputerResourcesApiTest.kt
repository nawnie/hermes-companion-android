package dev.hermeskotlin.core.resources

import dev.hermeskotlin.core.gateway.GatewayRepository
import dev.hermeskotlin.core.gateway.SavedGateway
import dev.hermeskotlin.core.network.createHttpClient
import dev.hermeskotlin.core.storage.InMemoryKeyValueStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComputerResourcesApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private suspend fun gateways(): GatewayRepository = GatewayRepository(InMemoryKeyValueStore()).apply {
        save(SavedGateway("https://hermes.example.ts.net:8447"))
    }

    @Test
    fun parsesLiveHostAndGpuCountersAsBytesAndConvertsThemToGiB() = runTest {
        val paths = mutableListOf<String>()
        val client = createHttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/api/system/stats" -> respond(
                    """{"cpu_percent":23.7,"cpu_count":16,"memory":{"total":34359738368,"available":17179869184,"used":17179869184,"percent":50.0}}""",
                    HttpStatusCode.OK,
                    json,
                )
                "/api/local-models/hardware" -> respond(
                    """{"uma":false,"vram_total_bytes":12884901888,"ram_total_bytes":34359738368,"ram_available_bytes":17179869184,"gpu_name":"NVIDIA Example","gpu_util_percent":53.2,"vram_used_bytes":6442450944}""",
                    HttpStatusCode.OK,
                    json,
                )
                else -> error("Unexpected telemetry route: ${request.url.encodedPath}")
            }
        })
        val api = ComputerResourcesApi(client, gateways()) { 1234L }

        val sample = api.read()

        assertEquals(listOf("/api/system/stats", "/api/local-models/hardware").toSet(), paths.toSet())
        assertEquals(1234L, sample.sampledAtMillis)
        assertEquals(23.7, sample.cpuPercent)
        assertEquals(16, sample.logicalCpuCount)
        assertEquals(17179869184L, sample.memoryUsedBytes)
        assertEquals(34359738368L, sample.memoryTotalBytes)
        assertEquals("NVIDIA Example", sample.gpuName)
        assertEquals(53.2, sample.gpuUtilPercent)
        assertEquals(6442450944L, sample.vramUsedBytes)
        assertEquals(12884901888L, sample.vramTotalBytes)
        assertEquals(32.0, sample.memoryTotalBytes!!.asGiB())
        assertEquals(12.0, sample.vramTotalBytes!!.asGiB())
        assertNull(sample.systemError)
        assertNull(sample.hardwareError)
    }

    @Test
    fun failedSystemEndpointLeavesCpuUnknownAndUsesReportedHardwareRam() = runTest {
        val client = createHttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/api/system/stats") {
                respond("""{"detail":"System telemetry unavailable"}""", HttpStatusCode.ServiceUnavailable, json)
            } else {
                respond(
                    """{"uma":true,"vram_total_bytes":8589934592,"ram_total_bytes":17179869184,"ram_available_bytes":6442450944,"gpu_name":null,"gpu_util_percent":null,"vram_used_bytes":null}""",
                    HttpStatusCode.OK,
                    json,
                )
            }
        })
        val api = ComputerResourcesApi(client, gateways())

        val sample = api.read()

        assertEquals("Can't reach the gateway: System telemetry unavailable", sample.systemError)
        assertNull(sample.cpuPercent)
        assertEquals(10737418240L, sample.memoryUsedBytes)
        assertEquals(17179869184L, sample.memoryTotalBytes)
        assertEquals(6442450944L, sample.memoryAvailableBytes)
        assertEquals(true, sample.gpuMemoryIsShared)
        assertNull(sample.gpuName)
        assertNull(sample.gpuUtilPercent)
        assertNull(sample.vramUsedBytes)
        assertEquals(8589934592L, sample.vramTotalBytes)
        assertEquals(8.0, sample.vramTotalBytes!!.asGiB())
        assertNull(sample.hardwareError)
    }
}
