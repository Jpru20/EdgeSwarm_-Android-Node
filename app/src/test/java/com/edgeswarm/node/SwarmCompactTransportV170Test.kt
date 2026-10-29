package com.edgeswarm.node

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class SwarmCompactTransportV170Test {
    @Test fun exactRoundtripAndKindBoundary() {
        val raw = """{"taskId":77,"prompt":"你好 🌍","model":"7b"}""".toByteArray(Charsets.UTF_8)
        for (kind in listOf(SwarmCompactTransportV170.Kind.TASK, SwarmCompactTransportV170.Kind.RESULT)) {
            val encoded = SwarmCompactTransportV170.encode(kind, raw)
            assertArrayEquals(raw, SwarmCompactTransportV170.decode(kind, encoded))
        }
        val result = SwarmCompactTransportV170.encode(SwarmCompactTransportV170.Kind.RESULT, raw)
        assertRejected { SwarmCompactTransportV170.decode(SwarmCompactTransportV170.Kind.TASK, result) }
    }

    @Test fun decodeNodeJsFixtures() {
        val task = "U1dDVAEBAAAAAAA9AAAAPdctp4+5ELWnbe62ft5HXZI0ptx5Da4h9QY9FGo0TWfxICB7InRhc2tJZCI6NzcsICJwcm9tcHQiOiLDhMOWIMOlIOS9oOWlvSDwn4yNIiwibW9kZWwiOiI3YiJ9Cg=="
        val result = "U1dDVAEDAQAAAABCAAAAU7A/FFUpxJRc4sW3pyd0fFTOmoaz2x6ZoNv+uUYiDUr/H4sIAAAAAAAAA1NQqFZKLClJzS0o8UxRslJKNFTSUUrMjM8vLSkoLVGyUvLJzEtVMIzJA9NGSjpKafn5JalFSlZKj+ZMUapV4AIAHWKwiEIAAAA="
        assertTrue(String(SwarmCompactTransportV170.decode(
            SwarmCompactTransportV170.Kind.TASK, Base64.getDecoder().decode(task)), Charsets.UTF_8).contains("你好 🌍"))
        assertTrue(String(SwarmCompactTransportV170.decode(
            SwarmCompactTransportV170.Kind.RESULT, Base64.getDecoder().decode(result)), Charsets.UTF_8).contains("✔"))
    }

    @Test fun negotiateAndCompressWhenBeneficial() {
        val json = "citation source evidence. ".repeat(600).toByteArray()
        assertFalse(SwarmCompactTransportV170.chooseResult(json, negotiated = false).compact)
        assertFalse(SwarmCompactTransportV170.chooseResult(ByteArray(4 * 1024 * 1024 + 1), negotiated = true).compact)
        val choice = SwarmCompactTransportV170.chooseResult(json, negotiated = true)
        assertTrue(choice.compact)
        assertTrue(choice.bytes.size < json.size)
        assertArrayEquals(json, SwarmCompactTransportV170.decode(SwarmCompactTransportV170.Kind.RESULT, choice.bytes))
        assertArrayEquals(json, SwarmCompactTransportV170.decodeTaskResponse("application/json; charset=utf-8", json))
    }

    @Test fun rejectCorruptionAndOversizedFrames() {
        val raw = "evidence".repeat(1000).toByteArray()
        val frame = SwarmCompactTransportV170.encode(SwarmCompactTransportV170.Kind.RESULT, raw)
        val corrupted = frame.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }
        assertRejected { SwarmCompactTransportV170.decode(SwarmCompactTransportV170.Kind.RESULT, corrupted) }
        val oversized = frame.copyOf().also { it[8] = 0x7f.toByte() }
        assertRejected { SwarmCompactTransportV170.decode(SwarmCompactTransportV170.Kind.RESULT, oversized) }
        assertRejected { SwarmCompactTransportV170.decode(SwarmCompactTransportV170.Kind.TASK, frame) }
    }

    private inline fun assertRejected(block: () -> Unit) {
        try { block(); fail("Expected the invalid frame to be rejected") }
        catch (_: IllegalArgumentException) { /* expected */ }
    }
}
