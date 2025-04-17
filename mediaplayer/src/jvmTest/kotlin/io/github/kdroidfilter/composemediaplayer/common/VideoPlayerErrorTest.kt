package io.github.kdroidfilter.composemediaplayer.common

import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the VideoPlayerError class
 */
class VideoPlayerErrorTest {

    /**
     * Test the creation of CodecError
     */
    @Test
    fun testCodecError() {
        val error = VideoPlayerError.CodecError("Unsupported codec")

        // Verify the error is initialized correctly
        assertTrue(error is VideoPlayerError.CodecError)
        assertEquals("Unsupported codec", error.message)

        // Test equality
        val sameError = VideoPlayerError.CodecError("Unsupported codec")
        val differentError = VideoPlayerError.CodecError("Different codec error")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of NetworkError
     */
    @Test
    fun testNetworkError() {
        val error = VideoPlayerError.NetworkError("Connection timeout")

        // Verify the error is initialized correctly
        assertTrue(error is VideoPlayerError.NetworkError)
        assertEquals("Connection timeout", error.message)

        // Test equality
        val sameError = VideoPlayerError.NetworkError("Connection timeout")
        val differentError = VideoPlayerError.NetworkError("Network unavailable")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of SourceError
     */
    @Test
    fun testSourceError() {
        val error = VideoPlayerError.SourceError("File not found")

        // Verify the error is initialized correctly
        assertTrue(error is VideoPlayerError.SourceError)
        assertEquals("File not found", error.message)

        // Test equality
        val sameError = VideoPlayerError.SourceError("File not found")
        val differentError = VideoPlayerError.SourceError("Invalid URL")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test the creation of UnknownError
     */
    @Test
    fun testUnknownError() {
        val error = VideoPlayerError.UnknownError("Unexpected error")

        // Verify the error is initialized correctly
        assertTrue(error is VideoPlayerError.UnknownError)
        assertEquals("Unexpected error", error.message)

        // Test equality
        val sameError = VideoPlayerError.UnknownError("Unexpected error")
        val differentError = VideoPlayerError.UnknownError("Another error")

        assertEquals(error, sameError)
        assertNotEquals(error, differentError)
    }

    /**
     * Test that different error types are not equal
     */
    @Test
    fun testDifferentErrorTypes() {
        val codecError = VideoPlayerError.CodecError("Codec error")
        val networkError = VideoPlayerError.NetworkError("Network error")
        val sourceError = VideoPlayerError.SourceError("Source error")
        val unknownError = VideoPlayerError.UnknownError("Unknown error")

        // Verify different error types are not equal
        assertTrue(codecError != networkError)
        assertTrue(codecError != sourceError)
        assertTrue(codecError != unknownError)
        assertTrue(networkError != sourceError)
        assertTrue(networkError != unknownError)
        assertTrue(sourceError != unknownError)
    }

    /**
     * Test when used in a when expression
     */
    @Test
    fun testWhenExpression() {
        val errors = listOf(
            VideoPlayerError.CodecError("Codec error"),
            VideoPlayerError.NetworkError("Network error"),
            VideoPlayerError.SourceError("Source error"),
            VideoPlayerError.UnknownError("Unknown error")
        )

        for (error in errors) {
            val message = when (error) {
                is VideoPlayerError.CodecError -> "Codec: ${error.message}"
                is VideoPlayerError.NetworkError -> "Network: ${error.message}"
                is VideoPlayerError.SourceError -> "Source: ${error.message}"
                is VideoPlayerError.UnknownError -> "Unknown: ${error.message}"
            }

            when (error) {
                is VideoPlayerError.CodecError -> assertEquals("Codec: Codec error", message)
                is VideoPlayerError.NetworkError -> assertEquals("Network: Network error", message)
                is VideoPlayerError.SourceError -> assertEquals("Source: Source error", message)
                is VideoPlayerError.UnknownError -> assertEquals("Unknown: Unknown error", message)
            }
        }
    }
}
