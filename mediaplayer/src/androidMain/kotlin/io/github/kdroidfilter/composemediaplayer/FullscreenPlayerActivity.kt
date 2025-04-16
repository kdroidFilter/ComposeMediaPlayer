package io.github.kdroidfilter.composemediaplayer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.media3.common.util.UnstableApi

/**
 * Activity that displays a video player in fullscreen mode.
 * This activity is launched when the user toggles fullscreen mode in the video player.
 */
@UnstableApi
class FullscreenPlayerActivity : ComponentActivity() {

    companion object {
        /**
         * Launch the fullscreen player activity
         *
         * @param context The context to use for launching the activity
         * @param playerState The player state to use in the fullscreen activity
         */
        fun launch(context: Context, playerState: VideoPlayerState) {
            // Register the player state to be accessible from the fullscreen activity
            VideoPlayerStateRegistry.registerState(playerState)

            // Launch the fullscreen activity
            val intent = Intent(context, FullscreenPlayerActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set fullscreen using modern API
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Defer the fullscreen setup to ensure window is fully initialized
        window.decorView.post {
            setupFullscreenMode()
        }

        // Register back button callback using the modern approach
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Exit fullscreen mode when back button is pressed
                VideoPlayerStateRegistry.getRegisteredState()?.isFullscreen = false
                finish()
            }
        })

        setContent {
            FullscreenPlayerContent()
        }
    }

    @Composable
    private fun FullscreenPlayerContent() {
        // Get the player state from the registry
        val playerState = remember { 
            VideoPlayerStateRegistry.getRegisteredState() 
        }

        // Handle back button press to exit fullscreen
        DisposableEffect(Unit) {
            onDispose {
                playerState?.isFullscreen = false
            }
        }

        // Display the video player in fullscreen
        playerState?.let { state ->
            VideoPlayerSurface(
                playerState = state,
                modifier = Modifier.fillMaxSize()
            )
        }
    }


    /**
     * Sets up fullscreen mode based on the Android version
     */
    private fun setupFullscreenMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // For Android 11+ (API 30+)
            try {
                val controller = window.insetsController
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                // Fallback to older method if there's any issue with the new API
                setLegacyFullscreenMode()
            }
        } else {
            // Fallback for older versions
            setLegacyFullscreenMode()
        }
    }

    /**
     * Sets up fullscreen mode using the legacy API for older Android versions
     */
    @Suppress("DEPRECATION")
    private fun setLegacyFullscreenMode() {
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the registered state when the activity is destroyed
        VideoPlayerStateRegistry.clearRegisteredState()
    }
}
