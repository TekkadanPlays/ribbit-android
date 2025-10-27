package com.example.views

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.views.ui.navigation.RibbitNavigation
import com.example.views.ui.theme.ViewsTheme
import com.example.views.viewmodel.AppViewModel
import com.example.views.viewmodel.AccountStateViewModel

/**
 * Main Activity for Ribbit Android app.
 *
 * This activity uses proper Jetpack Navigation Compose for state management,
 * allowing infinite exploration through feeds, threads, and profiles with
 * full navigation history preservation (like Primal app).
 */
class MainActivity : ComponentActivity() {

    // Activity result launcher for Amber login
    private val amberLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        onAmberLoginResult?.invoke(result.resultCode, result.data)
    }

    // Callback to handle login result
    private var onAmberLoginResult: ((Int, Intent?) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            ViewsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val appViewModel: AppViewModel = viewModel()
                    val accountStateViewModel: AccountStateViewModel = viewModel()

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
                }
            }
        }
    }
}
