package com.dashcam.dvr.telemetry.ntp

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * SntpClient — Simple Network Time Protocol client (RFC 4330)
 *
 * Blueprint §6 — Clock Synchronisation
 * ─────────────────────────────────────
 * Performs a single NTP request/response exchange and calculates:
 *   • Clock offset  — how far the device clock differs from true UTC
 *   • Round-trip    — network latency (used to assess offset accuracy)
 *
 * The classic 4-timestamp algorithm (Marzullo):
 *   t1 = client transmit time (device clock)
 *   t2 = server receive time  (NTP server clock)
 *   t3 = server transmit time (NTP server clock)
 *   t4 = client receive time  (device clock)
 *   offset    = ((t2 - t1) + (t3 - t4)) / 2
 *   roundTrip = (t4 - t1) - (t3 - t2)
 *
 * No third-party libraries — pure Java UDP socket; works on all Android versions.
 */
class SntpClient {

    data class SntpResult(
        val offsetMs:      Long,
        val roundTripMs:   Long,
        val serverAddress: String
    )

    fun sync(server: String): SntpResult? {
        return try {
            val address = InetAddress.getByName(server)
            val requestBuffer = ByteArray(PACKET_SIZE)
            // LI=0, VN=3 (NTPv3), Mode=3 (client) → 0b00_011_011 = 0x1B
            requestBuffer[0] = 0x1B.toByte()

            DatagramSocket().use { socket ->
                socket.soTimeout = TIMEOUT_MS

                val t1 = System.currentTimeMillis()
                socket.send(DatagramPacket(requestBuffer, PACKET_SIZE, address, NTP_PORT))

                val responseBuffer = ByteArray(PACKET_SIZE)
                val responsePacket = DatagramPacket(responseBuffer, PACKET_SIZE)
                socket.receive(responsePacket)
                val t4 = System.currentTimeMillis()

                val data = responsePacket.data
                val t2 = readTimestampMs(data, OFFSET_RECEIVE_TIME)
                val t3 = readTimestampMs(data, OFFSET_TRANSMIT_TIME)

                val offset    = ((t2 - t1) + (t3 - t4)) / 2
                val roundTrip = (t4 - t1) - (t3 - t2)

                Log.i(TAG, "NTP OK  server=$server  offset=${offset}ms  rtt=${roundTrip}ms")
                SntpResult(offsetMs = offset, roundTripMs = roundTrip, serverAddress = server)
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "NTP timeout — server=$server")
            null
        } catch (e: Exception) {
            Log.e(TAG, "NTP error — server=$server  reason=${e.message}")
            null
        }
    }

    private fun readTimestampMs(data: ByteArray, offset: Int): Long {
        var seconds  = 0L
        var fraction = 0L
        for (i in 0..3) {
            seconds  = (seconds  shl 8) or (data[offset + i].toLong() and 0xFF)
            fraction = (fraction shl 8) or (data[offset + 4 + i].toLong() and 0xFF)
        }
        val unixSeconds = seconds - NTP_EPOCH_OFFSET_S
        val fractionMs  = fraction * 1_000L / 0x100000000L
        return unixSeconds * 1_000L + fractionMs
    }

    companion object {
        private const val TAG                  = "SntpClient"
        private const val NTP_PORT             = 123
        private const val PACKET_SIZE          = 48
        private const val TIMEOUT_MS           = 5_000
        private const val NTP_EPOCH_OFFSET_S   = 2_208_988_800L
        private const val OFFSET_RECEIVE_TIME  = 32
        private const val OFFSET_TRANSMIT_TIME = 40
    }
}
