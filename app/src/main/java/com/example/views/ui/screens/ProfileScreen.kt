package com.example.views.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.views.ribbit.R
import com.example.views.data.Author
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.ModernSearchBar
import com.example.views.repository.ZapType
import com.example.views.ui.components.NoteCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    author: Author,
    authorNotes: List<Note>,
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onImageTap: (com.example.views.data.Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onReact: (Note, String) -> Unit = { _, _ -> },
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZap: (String, Long) -> Unit = { _, _ -> },
    isZapInProgress: (String) -> Boolean = { false },
    isZapped: (String) -> Boolean = { false },
    /** Amount (sats) the current user zapped per note ID; (noteId) -> amount or null. */
    myZappedAmountForNote: (String) -> Long? = { null },
    /** Override comment count per note ID (e.g. from ReplyCountCache); (noteId) -> count or null. */
    overrideReplyCountForNote: (String) -> Int? = { null },
    /** Override zap count and reactions per note ID (e.g. from NoteCountsRepository); (noteId) -> NoteCounts or null. */
    countsForNote: (String) -> com.example.views.repository.NoteCounts? = { null },
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    /** Called when user taps a relay orb to show relay info. When non-null, RelayInfoDialog is shown by the host. */
    onRelayClick: (relayUrl: String) -> Unit = {},
    /** Whether the current user follows this profile author (from kind-3 contact list). */
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    accountNpub: String? = null,
    listState: LazyListState = rememberLazyListState(),
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    onLoginClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Use predictive back for smooth gesture navigation
    androidx.activity.compose.BackHandler {
        onBackClick()
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "profile",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    // Search button for searching notes on user's profile
                    IconButton(onClick = { /* TODO: Search profile notes */ }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search profile",
                            tint = Color.White
                        )
                    }

                    // Profile/Login button
                    if (onLoginClick != null) {
                        IconButton(onClick = onLoginClick) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Profile",
                                tint = Color.White
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
        ) {
            // Profile Header
            item {
                ProfileHeader(
                    author = author,
                    notesCount = authorNotes.size,
                    isFollowing = isFollowing,
                    onFollowClick = onFollowClick,
                    onMessageClick = onMessageClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }

            // Notes List (stable keys for recomposition and scroll performance)
            items(authorNotes, key = { it.id }) { note ->
                NoteCard(
                    note = note,
                    onLike = onLike,
                    onShare = onShare,
                    onComment = onComment,
                    onReact = onReact,
                    onProfileClick = onProfileClick,
                    onNoteClick = onNoteClick,
                    onImageTap = onImageTap,
                    onOpenImageViewer = onOpenImageViewer,
                    onVideoClick = onVideoClick,
                    onCustomZapSend = onCustomZapSend,
                    onZap = onZap,
                    isZapInProgress = isZapInProgress(note.id),
                    isZapped = isZapped(note.id),
                    myZappedAmount = myZappedAmountForNote(note.id),
                    overrideReplyCount = overrideReplyCountForNote(note.id),
                    overrideZapCount = countsForNote(note.id)?.zapCount,
                    overrideReactions = countsForNote(note.id)?.reactions,
                    onRelayClick = onRelayClick,
                    accountNpub = accountNpub,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    author: Author,
    notesCount: Int = 0,
    isFollowing: Boolean = false,
    onFollowClick: () -> Unit = {},
    onMessageClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile Picture: avatar from kind-0 when available, else initial
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                if (author.avatarUrl != null) {
                    AsyncImage(
                        model = author.avatarUrl,
                        contentDescription = "Profile picture",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = author.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Name and verification
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = author.displayName,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    textAlign = TextAlign.Center
                )
                if (author.isVerified) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Verified",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Username
            Text(
                text = "@${author.username}",
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )

            // About / bio (kind-0)
            author.about?.takeIf { it.isNotBlank() }?.let { about ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = about,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Website link (kind-0)
            author.website?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(modifier = Modifier.height(8.dp))
                val uriHandler = LocalUriHandler.current
                val displayUrl = url.take(64) + if (url.length > 64) "…" else ""
                Text(
                    text = displayUrl,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { uriHandler.openUri(if (url.startsWith("http")) url else "https://$url") }
                )
            }

            // NIP-05 identifier (kind-0)
            author.nip05?.takeIf { it.isNotBlank() }?.let { nip05 ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = nip05,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Stats Row (Notes = loaded count; Followers/Following = placeholder until kind-3 aggregation)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Notes",
                    value = notesCount.toString(),
                    onClick = { /* Scroll to notes list */ }
                )
                StatItem(
                    label = "Followers",
                    value = "\u2013",
                    onClick = { /* TODO: Requires kind-3 index */ }
                )
                StatItem(
                    label = "Following",
                    value = "\u2013",
                    onClick = { /* TODO: Requires kind-3 index */ }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isFollowing) {
                    OutlinedButton(
                        onClick = onFollowClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Following")
                    }
                } else {
                    Button(
                        onClick = onFollowClick,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Follow")
                    }
                }
                OutlinedButton(
                    onClick = onMessageClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Message")
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Preview(showBackground = true)
@Composable
fun ProfileScreenPreview() {
    val sampleAuthor = SampleData.sampleNotes[0].author
    val sampleNotes = SampleData.sampleNotes.take(3)

    ProfileScreen(
        author = sampleAuthor,
        authorNotes = sampleNotes,
        onBackClick = {},
        onNoteClick = {},
        onLike = {},
        onShare = {},
        onComment = {},
        onProfileClick = {}
    )
}
