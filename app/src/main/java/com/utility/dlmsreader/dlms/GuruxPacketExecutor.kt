package com.utility.dlmsreader.dlms

import gurux.dlms.GXByteBuffer
import gurux.dlms.GXReplyData
import gurux.dlms.secure.GXDLMSSecureClient
import com.utility.dlmsreader.transport.OpticalUsbTransport
import kotlinx.coroutines.delay

class GuruxPacketExecutor(
    private val transport: OpticalUsbTransport,
    private val client: GXDLMSSecureClient,
    private val maxRetries: Int = 3
) {
    suspend fun readDlmsPacket(txBytes: ByteArray, reply: GXReplyData) {
        var attempt = 0
        var success = false
        val data = GXByteBuffer()

        while (attempt < maxRetries && !success) {
            try {
                attempt++
                // 1. Write the request frame to the optical probe
                transport.write(txBytes, 1000)

                // 2. Read loop until a complete HDLC frame / APDU is assembled
                data.clear()
                val timeout = System.currentTimeMillis() + 4000 // 4 second timeout per packet
                
                while (System.currentTimeMillis() < timeout) {
                    val chunk = transport.readBytesRaw(128, 200)
                    if (chunk.isNotEmpty()) {
                        data.set(chunk)
                        // Check if the frame is fully complete using Gurux client parser
                        if (client.getData(data, reply)) {
                            success = true
                            break
                        }
                    }
                }
                if (success) break
            } catch (e: Exception) {
                if (attempt >= maxRetries) throw e
                delay(250) // Brief pause before retry
            }
        }
        
        if (!success) {
            throw Exception("DLMS packet execution timed out after $maxRetries attempts.")
        }
    }
}
