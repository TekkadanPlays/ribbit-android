package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.example.views.ui.components.cutoutPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import com.example.views.data.Author
import com.example.views.repository.ProfileMetadataCache
import com.example.views.ribbit.BuildConfig
import com.example.views.ui.components.ProfilePicture
import com.example.views.ui.components.SupportRibbitZapDialog
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Developer npub (Tekkadan). */
private const val DEV_NPUB = "npub12zqf55l7l9vsg5f6ssx5pq4f9dzu6hcmnepkm8ftj25fecy379jqkq99h8"

/** Convert the npub to hex at compile-time-ish (lazy val). */
private val DEV_HEX_PUBKEY: String? by lazy {
    try {
        val parsed = Nip19Parser.uriToRoute(DEV_NPUB)
        (parsed?.entity as? NPub)?.hex?.lowercase()
    } catch (_: Exception) { null }
}

/** Well-known cache relays to try fetching the developer profile from. */
private val FALLBACK_CACHE_RELAYS = listOf(
    "wss://purplepag.es",
    "wss://relay.damus.io",
    "wss://relay.nostr.band",
    "wss://nos.lol"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBackClick: () -> Unit,
    onProfileClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val uriHandler = LocalUriHandler.current
    val profileCache = remember { ProfileMetadataCache.getInstance() }

    // Observe developer profile from cache; re-compose when it arrives
    var devAuthor by remember { mutableStateOf<Author?>(null) }

    // Try resolving from cache immediately
    LaunchedEffect(Unit) {
        val hex = DEV_HEX_PUBKEY ?: return@LaunchedEffect
        devAuthor = profileCache.getAuthor(hex)
        if (devAuthor == null) {
            // Fetch from well-known relays
            withContext(Dispatchers.IO) {
                profileCache.requestProfiles(listOf(hex), FALLBACK_CACHE_RELAYS)
            }
            devAuthor = profileCache.getAuthor(hex)
        }
    }

    // Also listen for profile updates (in case it arrives asynchronously)
    LaunchedEffect(Unit) {
        val hex = DEV_HEX_PUBKEY ?: return@LaunchedEffect
        profileCache.profileUpdated.collect { updatedKey ->
            if (updatedKey == hex) {
                devAuthor = profileCache.getAuthor(hex)
            }
        }
    }

    Scaffold(
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
                TopAppBar(
                    title = {
                        Text(
                            "About",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    },
                    windowInsets = WindowInsets(0),
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Frog emoji (our app icon theme)
            Text(
                text = "\uD83D\uDC38",
                fontSize = 72.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // App Name
            Text(
                text = "ribbit",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Version from BuildConfig (debug build shows suffix)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(
                    text = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (Debug)" else "",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(48.dp))

            // ── Developer profile section ──
            Text(
                text = "made with \uD83D\uDC9A by",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Profile card
            val author = devAuthor
            if (author != null) {
                // Avatar
                ProfilePicture(
                    author = author,
                    size = 72.dp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Display name
                Text(
                    text = author.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // NIP-05 if available
                author.nip05?.let { nip05 ->
                    Text(
                        text = nip05,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Bio
                author.about?.let { bio ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }
            } else {
                // Fallback: show "Tekkadan" while profile loads
                Text(
                    text = "Tekkadan",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // NPUB (always shown, selectable)
            SelectionContainer {
                Text(
                    text = DEV_NPUB,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // View profile button
            Button(
                onClick = {
                    DEV_HEX_PUBKEY?.let { onProfileClick(it) }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("View profile", fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Support Ribbit button
            var showSupportZapDialog by remember { mutableStateOf(false) }
            Button(
                onClick = { showSupportZapDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFE57373)
                ),
                modifier = Modifier.fillMaxWidth(0.7f)
            ) {
                Icon(
                    imageVector = Icons.Filled.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Support Ribbit", fontWeight = FontWeight.Medium)
            }

            if (showSupportZapDialog) {
                SupportRibbitZapDialog(onDismiss = { showSupportZapDialog = false })
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}


@Preview(showBackground = true)
@Composable
fun AboutScreenPreview() {
    MaterialTheme {
        AboutScreen(onBackClick = {})
    }
}
