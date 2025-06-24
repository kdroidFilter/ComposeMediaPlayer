package io.github.kdroidfilter.composemediaplayer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VideoPlayerErrorTest {

    @Test
    fun testCodecError() {
        val error = VideoPlayerError.CodecError("Unsupported codec")

        assertTrue(error is VideoPlayerError.CodecError)
        assertEquals("Unsupported codec", (error as VideoPlayerError.CodecError).message)
    }

    @Test
    fun testNetworkError() {
        val error = VideoPlayerError.NetworkError("Connection timeout")

        assertTrue(error is VideoPlayerError.NetworkError)
        assertEquals("Connection timeout", (error as VideoPlayerError.NetworkError).message)
    }

    @Test
    fun testSourceError() {
        val error = VideoPlayerError.SourceError("File not found")

        assertTrue(error is VideoPlayerError.SourceError)
        assertEquals("File not found", (error as VideoPlayerError.SourceError).message)
    }

    @Test
    fun testUnknownError() {
        val error = VideoPlayerError.UnknownError("Unexpected error")

        assertTrue(error is VideoPlayerError.UnknownError)
        assertEquals("Unexpected error", (error as VideoPlayerError.UnknownError).message)
    }

    @Test
    fun testErrorEquality() {
        val error1 = VideoPlayerError.CodecError("Same error")
        val error2 = VideoPlayerError.CodecError("Same error")
        val error3 = VideoPlayerError.CodecError("Different error")
        val error4 = VideoPlayerError.NetworkError("Same error")

        assertEquals(error1, error2, "Same error type and message should be equal")
        assertNotEquals(error1, error3, "Same error type but different message should not be equal")

        // For different types, we can just assert they're not the same object
        assertTrue(error1 != error4, "Different error type should not be equal")
    }

    @Test
    fun testErrorTypes() {
        val codecError = VideoPlayerError.CodecError("Codec error")
        val networkError = VideoPlayerError.NetworkError("Network error")
        val sourceError = VideoPlayerError.SourceError("Source error")
        val unknownError = VideoPlayerError.UnknownError("Unknown error")

        // Verify that each error is an instance of VideoPlayerError
        assertTrue(codecError is VideoPlayerError)
        assertTrue(networkError is VideoPlayerError)
        assertTrue(sourceError is VideoPlayerError)
        assertTrue(unknownError is VideoPlayerError)

        // Verify that errors of different types are not equal
        val errors = listOf(codecError, networkError, sourceError, unknownError)
        for (i in errors.indices) {
            for (j in errors.indices) {
                if (i != j) {
                    assertTrue(errors[i] != errors[j], 
                        "Different error types should not be equal: ${errors[i]} vs ${errors[j]}")
                }
            }
        }
    }
}
