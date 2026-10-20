package de.unger.camstream.ir

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get

class IrHttpClient(
    private val baseUrl: String
) {
    private val client = HttpClient(CIO)

    suspend fun setOn() {
        client.get("$baseUrl/ir/on")
    }

    suspend fun setOff() {
        client.get("$baseUrl/ir/off")
    }

    fun close() {
        client.close()
    }
}