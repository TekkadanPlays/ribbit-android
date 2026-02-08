package com.example.views

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.views.repository.NotesRepository
import com.example.views.repository.ProfileMetadataCache
import com.example.views.repository.TopicsRepository
import com.example.views.relay.RelayConnectionStateMachine
import com.example.views.services.RelayForegroundService
import com.example.views.ui.navigation.RibbitNavigation
import com.example.views.ui.theme.ViewsTheme
import com.example.views.ribbit.tsm.BuildConfig
import com.example.views.utils.AppMemoryTrimmer
import com.example.views.viewmodel.AppViewModel
import com.example.views.viewmodel.AccountStateViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import coil.Coil
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import android.Manifest

/**
 * Main Activity for Ribbit Android app.
 *
 * This activity uses proper Jetpack Navigation Compose for state management,
 * allowing infinite exploration through feeds, threads, and profiles with
 * full navigation history preservation (like Primal app).
 * Implements ComponentCallbacks2 to release memory on trim events.
 */
class MainActivity : ComponentActivity(), ComponentCallbacks2 {

    // Activity result launcher for Amber login
    private val amberLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onAmberLoginResult?.invoke(result.resultCode, result.data)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingStartAfterPermission && isInForeground) {
            pendingStartAfterPermission = false
            startRelayForegroundService()
        } else {
            pendingStartAfterPermission = false
            android.util.Log.w("MainActivity", "Notification permission denied; skipping foreground service")
        }
    }

    // Callback to handle login result
    private var onAmberLoginResult: ((Int, Intent?) -> Unit)? = null
    private var shouldRunRelayService = false
    private var pendingStartAfterPermission = false
    private var isInForeground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Detect main-thread disk/network violations in debug to avoid ANR regressions
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        // Register for memory trim callbacks so we can release caches when UI is hidden or system is under pressure
        registerComponentCallbacks(this)

        // Configure Coil with GIF decoder so animated GIFs render properly in feeds
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components {
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        add(ImageDecoderDecoder.Factory())
                    } else {
                        add(GifDecoder.Factory())
                    }
                }
                .crossfade(true)
                .build()
        )

        // Persist profile cache so avatars/display names survive process death; restore before feed
        ProfileMetadataCache.getInstance().init(applicationContext)

        // Persist feed so notes survive process death; restore on cold start
        NotesRepository.getInstance().prepareFeedCache(applicationContext)

        // Register kind-11 handler from app start so topics are collected before user opens Topics screen
        TopicsRepository.getInstance(applicationContext)

        // Re-apply relay subscription when app is resumed (e.g. after screen lock) so connection and notes resume
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RelayConnectionStateMachine.getInstance().requestReconnectOnResume()
            }
        })

        setContent {
            ViewsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val appViewModel: AppViewModel = viewModel()
                    val accountStateViewModel: AccountStateViewModel = viewModel()
                    val currentAccount by accountStateViewModel.currentAccount.collectAsState()

                    // Set up login result handler
                    onAmberLoginResult = { resultCode, data ->
                        accountStateViewModel.handleAmberLoginResult(resultCode, data)
                    }

                    // Use Jetpack Navigation for proper backstack management
                    RibbitNavigation(
                        appViewModel = appViewModel,
                        accountStateViewModel = accountStateViewModel,
                        onAmberLogin = { intent -> amberLoginLauncher.launch(intent) }
                    )

                    // Start/stop foreground service based on auth state
                    LaunchedEffect(currentAccount) {
                        setRelayServiceEnabled(currentAccount != null)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (shouldRunRelayService) {
            maybeStartRelayForegroundService()
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterComponentCallbacks(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> AppMemoryTrimmer.trimBackgroundCaches(level, this)
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> AppMemoryTrimmer.trimUiCaches(this)
            }
        } catch (e: Throwable) {
            android.util.Log.w("MainActivity", "onTrimMemory trim failed", e)
        }
    }

    private fun startRelayForegroundService() {
        val intent = Intent(this, RelayForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopRelayForegroundService() {
        stopService(Intent(this, RelayForegroundService::class.java))
    }

    private fun setRelayServiceEnabled(enabled: Boolean) {
        shouldRunRelayService = enabled
        if (!enabled) {
            stopRelayForegroundService()
            return
        }
        if (isInForeground) {
            maybeStartRelayForegroundService()
        }
    }

    private fun maybeStartRelayForegroundService() {
        if (Build.VERSION.SDK_INT >= 33) {
            val hasPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                pendingStartAfterPermission = true
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startRelayForegroundService()
    }
}
