package com.example.views.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlin.math.abs
import androidx.compose.ui.unit.offset
import androidx.compose.ui.graphics.Color
import com.example.views.ui.components.ClickableNoteContent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.example.views.repository.ProfileMetadataCache
import com.example.views.repository.ReactionsRepository
import com.example.views.utils.NoteContentBlock
import com.example.views.utils.normalizeAuthorIdForCache
import kotlinx.coroutines.flow.filter
import com.example.views.ui.theme.NoteBodyTextStyle
import com.example.views.utils.buildNoteContentWithInlinePreviews
import com.example.views.utils.UrlDetector
import coil.compose.AsyncImage
import com.example.views.ui.components.InlineVideoPlayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.views.data.Note
import com.example.views.data.QuotedNoteMeta
import com.example.views.repository.ZapType
import com.example.views.data.SampleData
import com.example.views.repository.QuotedNoteCache
import com.example.views.ui.icons.ArrowDownward
import com.example.views.ui.icons.ArrowUpward
import com.example.views.ui.icons.Bolt
import com.example.views.ui.icons.Bookmark
import com.example.views.ui.icons.ChatBubble
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ CRITICAL PERFORMANCE FIX: Cache SimpleDateFormat
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

/** Display name for author: prefer displayName, fall back to username, then short pubkey. */
private fun authorDisplayLabel(author: com.example.views.data.Author): String {
    val d = author.displayName
    // Only treat as placeholder if it looks like a truncated hex pubkey (8 hex chars + "...")
    val isPlaceholder = d.length == 11 && d.endsWith("...") && d.substring(0, 8).all { it in '0'..'9' || it in 'a'..'f' }
    if (!isPlaceholder) return d
    // displayName was a placeholder; try username instead
    val u = author.username
    val uIsPlaceholder = u.length == 11 && u.endsWith("...") && u.substring(0, 8).all { it in '0'..'9' || it in 'a'..'f' }
    return if (!uIsPlaceholder) u else author.id.take(8) + "..."
}


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteCard(
    note: Note,
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onReact: (Note, String) -> Unit = { _, _ -> },
    onProfileClick: (String) -> Unit = {},
    onNoteClick: (Note) -> Unit = {},
    /** Called when user taps the image (not the magnifier): (note, urls, index). E.g. feed = open thread, thread = open viewer. */
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    /** Called when user taps the magnifier on an image: open full viewer. */
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    /** Called when user taps a video: (urls, initialIndex). */
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    onZapSettings: () -> Unit = {},
    shouldCloseZapMenus: Boolean = false,
    /** Called when user taps a relay orb to show relay info. */
    onRelayClick: (relayUrl: String) -> Unit = {},
    /** Extra items for the 3-dot More menu (e.g. "Copy text" on thread view). */
    extraMoreMenuItems: List<Pair<String, () -> Unit>> = emptyList(),
    /** Current account npub for per-account recent emoji list. */
    accountNpub: String? = null,
    /** True while a zap is being sent for this note (shows loading on bolt). */
    isZapInProgress: Boolean = false,
    /** True if current user has zapped this note (bolt turns yellow). */
    isZapped: Boolean = false,
    /** Amount (sats) the current user zapped this note; shown as "You zapped X sats" when isZapped. */
    myZappedAmount: Long? = null,
    /** Override comment count (e.g. from ReplyCountCache when thread was loaded); used for counts row when non-null. */
    overrideReplyCount: Int? = null,
    /** Override zap count (e.g. from NoteCountsRepository kind-9735). */
    overrideZapCount: Int? = null,
    /** Override reaction emojis (e.g. from NoteCountsRepository kind-7). */
    overrideReactions: List<String>? = null,
    /** When false, hides counts row and action row (like/reply/zap/more); used for compact reply in thread. */
    showActionRow: Boolean = true,
    /** When set (e.g. thread view), author matching this id gets OP highlight and "OP" label; score • time shown on author line. */
    rootAuthorId: String? = null,
    /** When true (e.g. thread view), link embed shows expanded description. */
    expandLinkPreviewInThread: Boolean = false,
    /** When false, hides hashtags section below body (used in feed contexts). */
    showHashtagsSection: Boolean = true,
    /** Initial page index for the media album (shared state from AppViewModel). */
    initialMediaPage: Int = 0,
    /** Called when user swipes the media album to a different page. */
    onMediaPageChanged: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isZapMenuExpanded by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showReactionPicker by remember { mutableStateOf(false) }
    var showCustomZapDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var reactionEmoji by remember(note.id) { mutableStateOf(ReactionsRepository.getLastReaction(note.id)) }
    var recentEmojis by remember(accountNpub) { mutableStateOf(ReactionsRepository.getRecentEmojis(context, accountNpub)) }

    // Close zap menu when feed scrolls
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus) {
            isZapMenuExpanded = false
        }
    }
    // Author comes from note (repository updates list when profiles load); no per-card profileUpdated collector
    val displayAuthor = note.author
    val profileCache = ProfileMetadataCache.getInstance()
    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onNoteClick(note) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RectangleShape
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Repost header (kind-6) — compact inline row
            if (note.repostedBy != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 52.dp, end = 16.dp, top = 6.dp, bottom = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.width(4.dp))
                    ProfilePicture(
                        author = note.repostedBy!!,
                        size = 16.dp,
                        onClick = { onProfileClick(note.repostedBy!!.id) }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${note.repostedBy!!.displayName.ifBlank { note.repostedBy!!.username }} reposted",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            // Author info
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = if (note.repostedBy != null) 4.dp else 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar with shared element support
                ProfilePicture(
                    author = displayAuthor,
                    size = 40.dp,
                    onClick = { onProfileClick(note.author.id) }
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isOp = rootAuthorId != null && normalizeAuthorIdForCache(note.author.id) == normalizeAuthorIdForCache(rootAuthorId)
                            if (isOp) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        color = Color(0xFF8E30EB),
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = authorDisplayLabel(displayAuthor),
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "OP",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                Text(
                                    text = authorDisplayLabel(displayAuthor),
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                if (displayAuthor.isVerified) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Verified",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (rootAuthorId != null) {
                            val formattedTime = remember(note.timestamp) { formatTimestamp(note.timestamp) }
                            val score = note.likes
                            Text(
                                text = "$score • $formattedTime",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
                RelayOrbs(relayUrls = note.displayRelayUrls(), onRelayClick = onRelayClick)
            }

            val uriHandler = LocalUriHandler.current
            val hasBodyText = note.content.isNotBlank() || note.quotedEventIds.isNotEmpty()

            // Optional SUBJECT/TOPIC row (kind-11 / kind-1111)
            val topicTitle = note.topicTitle
            if (!topicTitle.isNullOrEmpty()) {
                Text(
                    text = topicTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            val firstPreview = note.urlPreviews.firstOrNull()

            // Counts row: above embed and body (when action row is shown)
            if (showActionRow) {
                val replyCountVal = (overrideReplyCount ?: note.comments).coerceAtLeast(0)
                val zapCount = (overrideZapCount ?: note.zapCount).coerceAtLeast(0)
                val reactionsList = overrideReactions ?: note.reactions
                val reactionCount = reactionsList.size.coerceAtLeast(0)
                val formattedTime = remember(note.timestamp) { formatTimestamp(note.timestamp) }
                // Colors for count numbers
                val ribbitGreen = Color(0xFF8FBC8F)
                val pastelRed = Color(0xFFE57373)
                val zapYellow = Color(0xFFFFD700)
                val mutedText = MaterialTheme.colorScheme.onSurfaceVariant
                val countStyle = MaterialTheme.typography.bodySmall
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: replies • timestamp
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (replyCountVal > 0) {
                            Text(text = "$replyCountVal", style = countStyle, color = ribbitGreen)
                            Text(text = " repl${if (replyCountVal == 1) "y" else "ies"}", style = countStyle, color = mutedText)
                            Text(text = " • ", style = countStyle, color = mutedText)
                        }
                        Text(text = formattedTime, style = countStyle, color = mutedText)
                    }
                    // Right side: reaction emojis • zaps
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (reactionCount > 0) {
                            // Show actual emoji characters (up to 5 unique), then count
                            val uniqueEmojis = reactionsList.distinct().take(5)
                            Text(
                                text = uniqueEmojis.joinToString(""),
                                style = countStyle.copy(fontSize = 13.sp)
                            )
                            if (reactionCount > 1) {
                                Text(text = " $reactionCount", style = countStyle, color = pastelRed)
                            }
                        }
                        if (reactionCount > 0 && zapCount > 0) {
                            Text(text = " • ", style = countStyle, color = mutedText)
                        }
                        if (zapCount > 0) {
                            Text(text = "$zapCount", style = countStyle, color = zapYellow)
                            Text(text = " zap${if (zapCount == 1) "" else "s"}", style = countStyle, color = mutedText)
                        }
                    }
                }
            }

            // HTML embed: below counts, above body text
            if (firstPreview != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Kind1LinkEmbedBlock(
                    previewInfo = firstPreview,
                    expandDescriptionInThread = expandLinkPreviewInThread,
                    inThreadView = expandLinkPreviewInThread,
                    onUrlClick = { url -> uriHandler.openUri(url) },
                    onNoteClick = { onNoteClick(note) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Body zone: only when there is text or quoted notes; otherwise embed/media only
            val linkStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
            // When firstPreview is shown as the top-level embed, pass its URL as "consumed"
            // so the text builder hides it from body text but does NOT add it to media groups
            // (the preview URL is a webpage, not an image — adding it to mediaUrls creates blank album entries)
            val consumedUrls = remember(firstPreview) {
                if (firstPreview != null) setOf(firstPreview.url) else emptySet()
            }
            // Track mentioned pubkeys so content rebuilds when their profiles load (npub→@displayName)
            val mentionedPubkeys = remember(note.content) {
                com.example.views.utils.extractPubkeysFromContent(note.content)
            }
            var mentionProfileVersion by remember { mutableStateOf(0) }
            if (mentionedPubkeys.isNotEmpty()) {
                LaunchedEffect(mentionedPubkeys) {
                    profileCache.profileUpdated
                        .filter { it in mentionedPubkeys.toSet() }
                        .collect { mentionProfileVersion++ }
                }
            }
            val contentBlocks = remember(note.content, note.mediaUrls, note.urlPreviews, consumedUrls, mentionProfileVersion) {
                buildNoteContentWithInlinePreviews(
                    note.content,
                    note.mediaUrls.toSet(),
                    note.urlPreviews,
                    linkStyle,
                    profileCache,
                    consumedUrls
                )
            }
            // Collect all media URLs across all MediaGroup blocks for fullscreen viewer
            val allMediaUrls = remember(contentBlocks) {
                contentBlocks.filterIsInstance<NoteContentBlock.MediaGroup>().flatMap { it.urls }
                    .ifEmpty { note.mediaUrls }
            }

            // Render interleaved content blocks: text surfaces, inline media carousels, and previews
            contentBlocks.forEach { block ->
                when (block) {
                    is NoteContentBlock.Content -> {
                        val annotated = block.annotated
                        if (annotated.isNotEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RectangleShape,
                                tonalElevation = 0.dp,
                                shadowElevation = 0.dp
                            ) {
                                ClickableNoteContent(
                                    text = annotated,
                                    style = NoteBodyTextStyle.copy(
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                    onClick = { offset ->
                                        val profile = annotated.getStringAnnotations(tag = "PROFILE", start = offset, end = offset).firstOrNull()
                                        val url = annotated.getStringAnnotations(tag = "URL", start = offset, end = offset).firstOrNull()
                                        val naddr = annotated.getStringAnnotations(tag = "NADDR", start = offset, end = offset).firstOrNull()
                                        when {
                                            profile != null -> onProfileClick(profile.item)
                                            url != null -> uriHandler.openUri(url.item)
                                            naddr != null -> uriHandler.openUri(naddr.item)
                                            else -> onNoteClick(note)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    is NoteContentBlock.MediaGroup -> {
                        // Inline album carousel at the correct position in the text flow
                        val mediaList = block.urls.take(10)
                        // Compute the starting index of this group within allMediaUrls for fullscreen viewer
                        val groupStartIndex = remember(allMediaUrls, mediaList) {
                            allMediaUrls.indexOf(mediaList.first()).coerceAtLeast(0)
                        }
                        val pagerState = rememberPagerState(
                            pageCount = { mediaList.size },
                            initialPage = initialMediaPage.coerceIn(0, (mediaList.size - 1).coerceAtLeast(0))
                        )
                        // Report page changes back so album position persists
                        LaunchedEffect(pagerState.currentPage) {
                            onMediaPageChanged(pagerState.currentPage)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                pageSpacing = 0.dp
                            ) { page ->
                                val offsetFromCenter = page - pagerState.currentPage - pagerState.currentPageOffsetFraction
                                val scale = 1f - 0.15f * abs(offsetFromCenter).coerceIn(0f, 1f)
                                val alphaVal = 1f - 0.25f * abs(offsetFromCenter).coerceIn(0f, 1f)
                                val url = mediaList[page]
                                val isVideo = UrlDetector.isVideoUrl(url)
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                            this.alpha = alphaVal
                                        }
                                        .clickable {
                                            // Open fullscreen viewer with ALL media from the note, starting at the correct index
                                            val fullIndex = groupStartIndex + page
                                            if (isVideo) onVideoClick(allMediaUrls, fullIndex) else onImageTap(note, allMediaUrls, fullIndex)
                                        }
                                ) {
                                    if (isVideo) {
                                        InlineVideoPlayer(
                                            url = url,
                                            modifier = Modifier.fillMaxSize(),
                                            onFullscreenClick = {
                                                val fullIndex = groupStartIndex + page
                                                onVideoClick(allMediaUrls, fullIndex)
                                            }
                                        )
                                    } else {
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        IconButton(
                                            onClick = {
                                                val fullIndex = groupStartIndex + page
                                                onOpenImageViewer(allMediaUrls, fullIndex)
                                            },
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(8.dp)
                                                .size(36.dp),
                                            colors = IconButtonDefaults.iconButtonColors(
                                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Search,
                                                contentDescription = "Open in viewer"
                                            )
                                        }
                                    }
                                }
                            }
                            if (mediaList.size > 1) {
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    repeat(mediaList.size) { index ->
                                        Box(
                                            modifier = Modifier
                                                .padding(horizontal = 3.dp)
                                                .size(if (pagerState.currentPage == index) 8.dp else 6.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (pagerState.currentPage == index)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is NoteContentBlock.Preview -> {
                        // Inline preview block skipped when we show top-right thumbnail
                        if (firstPreview == null) {
                            UrlPreviewCard(
                                previewInfo = block.previewInfo,
                                onUrlClick = { url -> uriHandler.openUri(url) },
                                onUrlLongClick = { _ -> }
                            )
                        }
                    }
                }
            }

            // Quoted notes (nostr:nevent1... / nostr:note1...)
            if (note.quotedEventIds.isNotEmpty()) {
                var quotedMetas by remember(note.id) { mutableStateOf<Map<String, QuotedNoteMeta>>(emptyMap()) }
                LaunchedEffect(note.quotedEventIds) {
                    note.quotedEventIds.forEach { id ->
                        if (id !in quotedMetas) {
                            val meta = QuotedNoteCache.get(id)
                            if (meta != null) quotedMetas = quotedMetas + (id to meta)
                        }
                    }
                }
                if (quotedMetas.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        quotedMetas.values.forEach { meta ->
                            val quotedAuthor = remember(meta.authorId) {
                                profileCache.resolveAuthor(meta.authorId)
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { /* Optional: open thread */ },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        ProfilePicture(
                                            author = quotedAuthor,
                                            size = 20.dp,
                                            onClick = { onProfileClick(meta.authorId) }
                                        )
                                        Text(
                                            text = quotedAuthor.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = meta.contentSnippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Hashtags
            if (showHashtagsSection && note.hashtags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = note.hashtags.joinToString(" ") { "#$it" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (showActionRow) {
            // Action buttons - 6 icons total with expanded hitboxes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Upvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowUpward,
                    contentDescription = "Upvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Downvote button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.ArrowDownward,
                    contentDescription = "Downvote",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // Bookmark button - expanded hitbox
                ActionButton(
                    icon = Icons.Outlined.Bookmark,
                    contentDescription = "Bookmark",
                    onClick = { /* Test button */ },
                    modifier = Modifier.weight(1f)
                )

                // React button (NIP-25) - shows emoji if reacted
                ReactionButton(
                    emoji = reactionEmoji,
                    onClick = { showReactionPicker = true },
                    modifier = Modifier.weight(1f)
                )

                // Zap button - tap to expand menu, long-press for custom zap dialog; loading + zapped state
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .combinedClickable(
                            onClick = { isZapMenuExpanded = !isZapMenuExpanded },
                            onLongClick = { showCustomZapDialog = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isZapInProgress -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        else -> Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = "Zap",
                            tint = if (isZapped) Color(0xFFFFD700) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // More options menu - expanded hitbox (extra items e.g. Copy text on thread view)
                Box(modifier = Modifier.weight(1f)) {
                    ActionButton(
                        icon = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        onClick = {
                            if (extraMoreMenuItems.isNotEmpty()) showMoreMenu = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (extraMoreMenuItems.isNotEmpty()) {
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            extraMoreMenuItems.forEach { (label, action) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        action()
                                        showMoreMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Zap menu - completely separate, appears below action buttons; Custom chip opens dialog
            ZapMenuRow(
                isExpanded = isZapMenuExpanded,
                onExpandedChange = { isZapMenuExpanded = it },
                onZap = { amount -> onZap(note.id, amount) },
                onCustomZap = { showCustomZapDialog = true; onCustomZap(note.id) },
                onSettingsClick = onZapSettings
            )
            }

            if (showCustomZapDialog) {
                ZapCustomDialog(
                    onDismiss = { showCustomZapDialog = false },
                    onSendZap = { amount, zapType, message ->
                        showCustomZapDialog = false
                        onCustomZapSend?.invoke(note, amount, zapType, message)
                    }
                )
            }

            if (showReactionPicker) {
                EmojiPickerDialog(
                    recentEmojis = recentEmojis,
                    onDismiss = { showReactionPicker = false },
                    onEmojiSelected = { emoji ->
                        reactionEmoji = emoji
                        showReactionPicker = false
                        ReactionsRepository.recordEmoji(context, accountNpub, emoji)
                        recentEmojis = ReactionsRepository.getRecentEmojis(context, accountNpub)
                        onReact(note, emoji)
                    },
                    onSaveDefaultEmoji = { emoji ->
                        // Save as a default (adds to front of recent list)
                        ReactionsRepository.recordEmoji(context, accountNpub, emoji)
                        recentEmojis = ReactionsRepository.getRecentEmojis(context, accountNpub)
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ EXPANDED HITBOX: Wider touch target to prevent accidental card activation
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ReactionButton(
    emoji: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (emoji.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.FavoriteBorder,
                contentDescription = "React",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Text(
                text = emoji,
                fontSize = 20.sp
            )
        }
    }
}

/** Returns the first grapheme (code point) of the string, or null if empty/blank. */
internal fun firstGrapheme(s: String): String? {
    val t = s.trim()
    if (t.isEmpty()) return null
    val end = Character.offsetByCodePoints(t, 0, 1).coerceIn(1, t.length)
    return t.substring(0, end)
}

@Composable
private fun ReactionPickerDialog(
    recentEmojis: List<String>,
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
    onSaveDefaultEmoji: ((String) -> Unit)? = null
) {
    var customEmoji by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                // Header
                Text(
                    text = "React",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Choose a reaction or enter a custom emoji.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Recent emojis (wrap when many)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    recentEmojis.forEach { emoji ->
                        Text(
                            text = emoji,
                            fontSize = 28.sp,
                            modifier = Modifier.clickable { onEmojiSelected(emoji) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom emoji input with + save button (type freely; we use first character when sending/saving)
                OutlinedTextField(
                    value = customEmoji,
                    onValueChange = { customEmoji = it },
                    label = { Text("Custom emoji") },
                    placeholder = { Text("\uD83D\uDE48") }, // 🙈
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (firstGrapheme(customEmoji) != null && onSaveDefaultEmoji != null) {
                            IconButton(
                                onClick = { firstGrapheme(customEmoji)?.let { onSaveDefaultEmoji(it) } }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Save as default",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            firstGrapheme(customEmoji)?.let { onEmojiSelected(it) }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = firstGrapheme(customEmoji) != null
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "now"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}h"
        else -> dateFormatter.format(Date(timestamp))
    }
}

@Preview(showBackground = true)
@Composable
fun NoteCardPreview() {
    NoteCard(note = SampleData.sampleNotes[0])
}
