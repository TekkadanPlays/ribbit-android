package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import com.example.views.ui.components.cutoutPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.views.ui.components.InlineVideoPlayer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoContentViewerScreen(
    urls: List<String>,
    initialIndex: Int,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (urls.isEmpty()) {
        LaunchedEffect(Unit) { onBackClick() }
        return
    }
    val pagerState = rememberPagerState(
        pageCount = { urls.size },
        initialPage = initialIndex.coerceIn(0, urls.size - 1)
    )

    // Consume back gesture so it only closes the viewer without animation
    BackHandler(onBack = onBackClick)

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            val url = urls[page]
            val isCurrentPage = pagerState.currentPage == page
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                InlineVideoPlayer(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                    autoPlay = true,
                    isVisible = isCurrentPage,
                    onExitFullscreen = onBackClick
                )
            }
        }

        Column(Modifier.statusBarsPadding()) {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}
