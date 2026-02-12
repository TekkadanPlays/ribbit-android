package com.example.views.ui.screens

import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.example.views.data.Author
import com.example.views.data.Comment
import com.example.views.data.Note
import com.example.views.viewmodel.Kind1RepliesViewModel
import com.example.views.viewmodel.ThreadRepliesUiState
import com.example.views.data.ThreadReply
import com.example.views.data.ThreadedReply
import com.example.views.data.toThreadReplyForThread
import com.example.views.data.toNote
import com.example.views.data.SampleData
import com.example.views.repository.RelayStorageManager
import com.example.views.ui.components.AdaptiveHeader
import com.example.views.ui.components.BottomNavigationBar
import com.example.views.repository.ZapType
import com.example.views.ui.components.NoteCard
import com.example.views.ui.components.ProfilePicture
import com.example.views.ui.components.RelayOrbs
import com.example.views.ui.components.ZapButtonWithMenu
import com.example.views.ui.components.ZapMenuRow
import com.example.views.ui.icons.ArrowDownward
import com.example.views.ui.theme.SageGreen80
import com.example.views.ui.icons.ArrowUpward
import com.example.views.ui.icons.Bolt
import com.example.views.ui.icons.Bookmark
import com.example.views.viewmodel.ThreadRepliesViewModel
import com.example.views.viewmodel.Kind1ReplySortOrder
import com.example.views.viewmodel.ReplySortOrder
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

// ✅ PERFORMANCE: Cached date formatter
private val dateFormatter by lazy { SimpleDateFormat("MMM d", Locale.getDefault()) }

// ✅ PERFORMANCE: Consistent animation specs
private val standardAnimation = tween<IntSize>(durationMillis = 200, easing = FastOutSlowInEasing)
private val fastAnimation = tween<IntSize>(durationMillis = 150, easing = FastOutSlowInEasing)

/** Thread/topic reply separator line color (Ribbit sage theme). */
private val ThreadLineColor = SageGreen80

/** Max indent depth; beyond this show "Read N more replies" and open sub-thread on tap. */
private const val MAX_THREAD_DEPTH = 4

/** Stable key for a reply so optimistic→real replacement doesn't change list key (avoids UI jump).
 *  Use the event id when available (unique per nostr event). Only fall back to content-based key
 *  for optimistic replies whose id is a synthetic UUID (starts with "optimistic-"). */
private fun logicalReplyKey(reply: ThreadReply): String =
    if (reply.id.startsWith("optimistic-")) {
        "logical:${reply.author.id}:${reply.content.take(80)}:${reply.replyToId}"
    } else {
        "reply:${reply.id}"
    }

/** Find a ThreadedReply node by reply id in the tree. */
private fun findThreadedReplyById(tree: List<ThreadedReply>, id: String): ThreadedReply? =
    tree.firstOrNull { it.reply.id == id } ?: tree.firstNotNullOfOrNull { findThreadedReplyById(it.children, id) }

/** Path of reply ids from root to target (inclusive); null if target not in tree. Used to expand and scroll to a reply. */
private fun findPathToReplyId(roots: List<ThreadedReply>, targetId: String): List<String>? {
    for (node in roots) {
        if (node.reply.id == targetId) return listOf(targetId)
        findPathToReplyId(node.children, targetId)?.let { sub -> return listOf(node.reply.id) + sub }
    }
    return null
}

/** Subtree under the given reply as the new root: same nesting (indent/lines), only that ROOT and its children. */
private fun subtreeWithStructure(tree: List<ThreadedReply>, focusReplyId: String): List<ThreadedReply>? {
    val node = findThreadedReplyById(tree, focusReplyId) ?: return null
    fun relevel(n: ThreadedReply, newLevel: Int): ThreadedReply = ThreadedReply(
        reply = n.reply,
        children = n.children.map { relevel(it, newLevel + 1) },
        level = newLevel
    )
    return listOf(relevel(node, 0))
}

// CommentState is imported from ThreadViewScreen.kt

/**
 * Modern, performant Thread View Screen following Material Design 3 principles
 *
 * Key Performance Improvements:
 * - Single animation spec for consistency
 * - Simplified state management
 * - Reduced recompositions
 * - Clean visual hierarchy
 * - Smooth animations without conflicts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernThreadViewScreen(
    note: Note,
    comments: List<CommentThread>,
    listState: LazyListState = rememberLazyListState(),
    commentStates: MutableMap<String, CommentState> = remember { mutableStateMapOf() },
    expandedControlsCommentId: String? = null,
    onExpandedControlsChange: (String?) -> Unit = {},
    /** Reply ID whose controls (like/reply/zap) are shown; null = all compact. Tap reply to expand. */
    expandedControlsReplyId: String? = null,
    onExpandedControlsReplyChange: (String?) -> Unit = {},
    topAppBarState: TopAppBarState = rememberTopAppBarState(),
    replyKind: Int = 1111, // 1 = Kind 1 replies (home feed), 1111 = Kind 1111 replies (topics)
    /** When set (e.g. from notification), expand path to this reply and scroll to it. */
    highlightReplyId: String? = null,
    threadRepliesViewModel: ThreadRepliesViewModel = viewModel(),
    kind1RepliesViewModel: Kind1RepliesViewModel = viewModel(),
    relayUrls: List<String> = emptyList(),
    cacheRelayUrls: List<String> = emptyList(),
    onBackClick: () -> Unit = {},
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    /** Navigate to a different note's thread (e.g. tapping a quoted note). */
    onNoteClick: (Note) -> Unit = {},
    onImageTap: (Note, List<String>, Int) -> Unit = { _, _, _ -> },
    onOpenImageViewer: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    onReact: (Note, String) -> Unit = { _, _ -> },
    onCustomZapSend: ((Note, Long, ZapType, String) -> Unit)? = null,
    /** When user taps a zap amount chip; (noteId, amount). */
    onZap: (String, Long) -> Unit = { _, _ -> },
    /** When non-null, used to resolve (noteId, amount) to sendZap(Note, amount) for root and replies. */
    onSendZap: ((Note, Long) -> Unit)? = null,
    /** Note IDs currently sending a zap (for loading indicator). */
    zapInProgressNoteIds: Set<String> = emptySet(),
    /** Note IDs the current user has zapped (bolt turns yellow). */
    zappedNoteIds: Set<String> = emptySet(),
    /** Amount (sats) the current user zapped per note ID; for "You zapped X sats". */
    myZappedAmountByNoteId: Map<String, Long> = emptyMap(),
    onCommentLike: (String) -> Unit = {},
    onCommentReply: (String) -> Unit = {},
    /** When non-null and replyKind==1111, enables kind-1111 reply dialog and publish. Returns error message or null on success. */
    onPublishThreadReply: ((rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, content: String) -> String?)? = null,
    /** When set, opens reply in a dedicated screen instead of in-dialog (replyToNote shown at top). */
    onOpenReplyCompose: ((rootId: String, rootPubkey: String, parentId: String?, parentPubkey: String?, replyToNote: Note?) -> Unit)? = null,
    onLoginClick: (() -> Unit)? = null,
    isGuest: Boolean = true,
    userDisplayName: String? = null,
    userAvatarUrl: String? = null,
    onHeaderProfileClick: () -> Unit = {},
    onHeaderAccountsClick: () -> Unit = {},
    onHeaderQrCodeClick: () -> Unit = {},
    onHeaderSettingsClick: () -> Unit = {},
    accountNpub: String? = null,
    /** Current user Author for optimistic reply (kind-1111); when set, reply appears immediately. */
    currentUserAuthor: Author? = null,
    /** Retrieve the shared media album page for a note (from AppViewModel). */
    mediaPageForNote: (String) -> Int = { 0 },
    /** Store the media album page when user swipes (to AppViewModel). */
    onMediaPageChanged: (String, Int) -> Unit = { _, _ -> },
    onRelayNavigate: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isRefreshing by remember { mutableStateOf(false) }
    /** Stack of reply ids for sub-thread drill-down; back gesture pops one. Empty = full thread. */
    var rootReplyIdStack by remember { mutableStateOf<List<String>>(emptyList()) }
    val currentRootReplyId = rootReplyIdStack.lastOrNull()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Select appropriate ViewModel based on reply kind
    val repliesState = when (replyKind) {
        1 -> {
            // Kind 1 replies for home feed (NIP-10 threaded when root/reply data present)
            val kind1State by kind1RepliesViewModel.uiState.collectAsState()
            ThreadRepliesUiState(
                note = kind1State.note,
                replies = kind1State.replies.map { it.toThreadReplyForThread() },
                threadedReplies = kind1State.threadedReplies,
                isLoading = kind1State.isLoading,
                error = kind1State.error,
                totalReplyCount = kind1State.totalReplyCount
            )
        }
        else -> {
            // Kind 1111 replies for topics
            threadRepliesViewModel.uiState.collectAsState().value
        }
    }

    // Set cache relay URLs for kind-0 profile fetches in reply ViewModels
    LaunchedEffect(cacheRelayUrls) {
        if (cacheRelayUrls.isNotEmpty()) {
            kind1RepliesViewModel.setCacheRelayUrls(cacheRelayUrls)
            threadRepliesViewModel.setCacheRelayUrls(cacheRelayUrls)
        }
    }

    // Preload outbox relays for quoted note authors (outbox model for faster reply loading)
    LaunchedEffect(note.id) {
        val discoveryRelays = listOf("wss://purplepag.es", "wss://user.kindpag.es") + cacheRelayUrls.take(2)
        // Extract quoted note author pubkeys from the note's tags
        note.quotedEventIds.forEach { quotedId ->
            val quotedMeta = com.example.views.repository.QuotedNoteCache.getCached(quotedId)
            if (quotedMeta != null && quotedMeta.authorId.isNotBlank()) {
                com.example.views.repository.Nip65RelayListRepository.fetchOutboxRelaysForAuthor(
                    quotedMeta.authorId, discoveryRelays
                )
            }
        }
    }

    // Load replies when screen opens; clear other kind's state so we don't show stale replies.
    // Guard: skip clear+reload if the active ViewModel already has replies for this note
    // (e.g. returning from reply_compose — don't wipe existing replies).
    val relayUrlsKey = remember(relayUrls) { relayUrls.sorted().joinToString(",") }
    LaunchedEffect(note.id, relayUrlsKey, replyKind) {
        // Skip when relay URLs haven't resolved yet — effect will re-fire when they arrive
        if (relayUrls.isEmpty()) {
            android.util.Log.d("ThreadView", "note=${note.id.take(8)} waiting for relay URLs... relayUrlsKey='$relayUrlsKey'")
            return@LaunchedEffect
        }
        val alreadyLoaded = when (replyKind) {
            1 -> kind1RepliesViewModel.uiState.value.note?.id == note.id && kind1RepliesViewModel.uiState.value.replies.isNotEmpty()
            else -> threadRepliesViewModel.uiState.value.note?.id == note.id && threadRepliesViewModel.uiState.value.replies.isNotEmpty()
        }
        android.util.Log.d("ThreadView", "note=${note.id.take(8)} replyKind=$replyKind relays=${relayUrls.size} alreadyLoaded=$alreadyLoaded relayUrls=${relayUrls.take(3)}")
        if (!alreadyLoaded) {
            when (replyKind) {
                1 -> threadRepliesViewModel.clearRepliesForNote(note.id)
                else -> kind1RepliesViewModel.clearRepliesForNote(note.id)
            }
            android.util.Log.d("ThreadView", "LOADING replies: note=${note.id.take(8)} replyKind=$replyKind via ${if (replyKind == 1) "kind1RepliesVM" else "threadRepliesVM"}")
            when (replyKind) {
                1 -> kind1RepliesViewModel.loadRepliesForNote(note, relayUrls)
                else -> threadRepliesViewModel.loadRepliesForNote(note, relayUrls)
            }
        }
    }

    // Subscribe to kind-7/9735 counts for root + reply note IDs so reactions/zaps show on thread replies
    // Key on totalReplyCount (not list ref) to avoid recomputing when the same replies re-emit
    val threadNoteRelays = remember(repliesState.totalReplyCount, note.id) {
        val map = mutableMapOf<String, List<String>>()
        map[note.id] = note.relayUrls.ifEmpty { listOfNotNull(note.relayUrl) }
        repliesState.replies.forEach { reply ->
            map[reply.id] = reply.relayUrls
        }
        map.toMap()
    }
    LaunchedEffect(threadNoteRelays) {
        com.example.views.repository.NoteCountsRepository.setThreadNoteIdsOfInterest(threadNoteRelays)
    }
    DisposableEffect(Unit) {
        onDispose { com.example.views.repository.NoteCountsRepository.setThreadNoteIdsOfInterest(emptyMap()) }
    }

    val noteCountsByNoteId by com.example.views.repository.NoteCountsRepository.countsByNoteId.collectAsState()

    // ✅ ZAP MENU AWARENESS: Global state for zap menu closure (like feed cards)
    var shouldCloseZapMenus by remember { mutableStateOf(false) }
    var expandedZapMenuCommentId by remember { mutableStateOf<String?>(null) }
    /** Reply ID whose "View relays" row is shown; toggled from dropdown. */
    var expandedRelaysReplyId by remember { mutableStateOf<String?>(null) }

    // ✅ ZAP CONFIGURATION: Dialog state for editing zap amounts
    var showZapConfigDialog by remember { mutableStateOf(false) }
    var showWalletConnectDialog by remember { mutableStateOf(false) }
    var showCopyTextDialog by remember { mutableStateOf(false) }
    var copyTextContent by remember { mutableStateOf("") }
    val clipboardManager = remember { context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    // Kind-1111 reply dialog (topic thread reply)
    var showReplyDialog by remember { mutableStateOf(false) }
    var parentReplyId by remember { mutableStateOf<String?>(null) }
    val effectiveOnComment: (String) -> Unit = if (replyKind == 1111 && (onOpenReplyCompose != null || onPublishThreadReply != null)) {
        { id ->
            if (id == note.id) {
                if (onOpenReplyCompose != null) {
                    onOpenReplyCompose(note.id, note.author.id, null, null, null)
                } else {
                    parentReplyId = null
                    showReplyDialog = true
                }
            } else onComment(id)
        }
    } else {
        onComment
    }
    val effectiveOnCommentReply: (String) -> Unit = if (replyKind == 1111 && (onOpenReplyCompose != null || onPublishThreadReply != null)) {
        { replyId ->
            if (onOpenReplyCompose != null) {
                val parent = repliesState.replies.find { it.id == replyId }
                onOpenReplyCompose(note.id, note.author.id, replyId, parent?.author?.id, parent?.toNote())
            } else {
                parentReplyId = replyId
                showReplyDialog = true
            }
        }
    } else {
        onCommentReply
    }
    val effectiveOnZap: (String, Long) -> Unit = if (onSendZap != null) {
        { nId, amount ->
            if (nId == note.id) onSendZap(note, amount)
            else repliesState.replies.find { it.id == nId }?.toNote()?.let { onSendZap(it, amount) }
        }
    } else {
        onZap
    }

    // ✅ ZAP MENU AWARENESS: Close zap menus when scrolling starts (like feed cards)
    var wasScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !wasScrolling) {
            // Scroll just started - close zap menus immediately
            shouldCloseZapMenus = true
            expandedZapMenuCommentId = null
            kotlinx.coroutines.delay(100)
            shouldCloseZapMenus = false
        }
        wasScrolling = listState.isScrollInProgress
    }

    // Predictive back: from sub-thread pop one level; from full thread exit screen
    BackHandler(enabled = rootReplyIdStack.isNotEmpty()) {
        rootReplyIdStack = rootReplyIdStack.dropLast(1)
    }
    BackHandler(enabled = rootReplyIdStack.isEmpty()) {
        onBackClick()
    }

    // When opened from notification with highlightReplyId (kind-1111), expand path to that reply and scroll to it
    val displayThreadedForHighlight = when (replyKind) {
        1 -> emptyList()
        else -> if (repliesState.threadedReplies.isNotEmpty()) repliesState.threadedReplies
                else repliesState.replies.map { ThreadedReply(reply = it, children = emptyList(), level = 0) }
    }
    val displayListForHighlight = when {
        currentRootReplyId != null -> subtreeWithStructure(displayThreadedForHighlight, currentRootReplyId!!) ?: displayThreadedForHighlight
        else -> displayThreadedForHighlight
    }
    var haveScrolledToHighlight by remember(highlightReplyId) { mutableStateOf(false) }
    LaunchedEffect(displayListForHighlight, highlightReplyId, replyKind, haveScrolledToHighlight) {
        if (highlightReplyId == null || replyKind != 1111 || haveScrolledToHighlight || displayListForHighlight.isEmpty()) return@LaunchedEffect
        val path = findPathToReplyId(displayThreadedForHighlight, highlightReplyId!!) ?: return@LaunchedEffect
        path.forEach { id -> commentStates[id] = CommentState(isCollapsed = false, isExpanded = true) }
        val listIndex = displayListForHighlight.indexOfFirst { it.reply.id == highlightReplyId }
        if (listIndex >= 0) {
            kotlinx.coroutines.delay(100)
            // LazyColumn has item(main_note)=0, item(replies_section)=1, then itemsIndexed(displayList)
            val scrollIndex = 2 + listIndex
            listState.animateScrollToItem(scrollIndex)
        }
        haveScrolledToHighlight = true
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AdaptiveHeader(
                title = "thread",
                showBackArrow = true,
                onBackClick = onBackClick,
                onLoginClick = onLoginClick,
                onProfileClick = onHeaderProfileClick,
                onAccountsClick = onHeaderAccountsClick,
                onQrCodeClick = onHeaderQrCodeClick,
                onSettingsClick = onHeaderSettingsClick,
                scrollBehavior = scrollBehavior,
                isGuest = isGuest,
                userDisplayName = userDisplayName,
                userAvatarUrl = userAvatarUrl
            )
        },
        floatingActionButton = {
            if (onOpenReplyCompose != null || onPublishThreadReply != null) {
                FloatingActionButton(
                    onClick = {
                        if (onOpenReplyCompose != null) {
                            onOpenReplyCompose(note.id, note.author.id, null, null, null)
                        } else {
                            parentReplyId = null
                            showReplyDialog = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Outlined.Reply, contentDescription = "Reply to thread")
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp)
        ) {
            // Main note card - no pull to refresh, uses predictive back
            item(key = "main_note") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NoteCard(
                        note = note,
                        onLike = onLike,
                        onShare = onShare,
                        onComment = effectiveOnComment,
                        onReact = onReact,
                        onProfileClick = onProfileClick,
                        onNoteClick = onNoteClick,
                        onImageTap = onImageTap,
                        onOpenImageViewer = onOpenImageViewer,
                        onVideoClick = onVideoClick,
                        onCustomZapSend = onCustomZapSend,
                        onZap = effectiveOnZap,
                        isZapInProgress = note.id in zapInProgressNoteIds,
                        isZapped = note.id in zappedNoteIds,
                        myZappedAmount = myZappedAmountByNoteId[note.id],
                        overrideReplyCount = repliesState.totalReplyCount,
                        overrideZapCount = noteCountsByNoteId[note.id]?.zapCount,
                        overrideZapTotalSats = noteCountsByNoteId[note.id]?.zapTotalSats,
                        overrideReactions = noteCountsByNoteId[note.id]?.reactions,
                        overrideReactionAuthors = noteCountsByNoteId[note.id]?.reactionAuthors,
                        overrideZapAuthors = noteCountsByNoteId[note.id]?.zapAuthors,
                        overrideZapAmountByAuthor = noteCountsByNoteId[note.id]?.zapAmountByAuthor,
                        overrideCustomEmojiUrls = noteCountsByNoteId[note.id]?.customEmojiUrls,
                        onRelayClick = onRelayNavigate,
                        shouldCloseZapMenus = shouldCloseZapMenus,
                        accountNpub = accountNpub,
                        expandLinkPreviewInThread = true,
                        showHashtagsSection = false,
                        initialMediaPage = mediaPageForNote(note.id),
                        onMediaPageChanged = { page -> onMediaPageChanged(note.id, page) },
                        extraMoreMenuItems = listOf(
                            Pair("Copy text") {
                                copyTextContent = note.content
                                showCopyTextDialog = true
                            }
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Modern divider
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Threaded replies section with loading state
            item(key = "replies_section") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // Reply count header (no loader; fallback message for no replies only)
                    if (repliesState.totalReplyCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val directCount = repliesState.replies.size
                            val totalCount = repliesState.totalReplyCount
                            Text(
                                text = when {
                                    totalCount <= 1 -> if (totalCount == 1) "1 reply" else ""
                                    directCount == totalCount -> "$totalCount replies"
                                    else -> "$directCount replies, $totalCount total"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )

                            // Sort button
                            if (repliesState.totalReplyCount > 0) {
                                var showSortMenu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { showSortMenu = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sort,
                                            contentDescription = "Sort",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    DropdownMenu(
                                        expanded = showSortMenu,
                                        onDismissRequest = { showSortMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Oldest first") },
                                            onClick = {
                                                if (replyKind == 1) kind1RepliesViewModel.setSortOrder(Kind1ReplySortOrder.CHRONOLOGICAL)
                                                else threadRepliesViewModel.setSortOrder(ReplySortOrder.CHRONOLOGICAL)
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Newest first") },
                                            onClick = {
                                                if (replyKind == 1) kind1RepliesViewModel.setSortOrder(Kind1ReplySortOrder.REVERSE_CHRONOLOGICAL)
                                                else threadRepliesViewModel.setSortOrder(ReplySortOrder.REVERSE_CHRONOLOGICAL)
                                                showSortMenu = false
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Most liked") },
                                            onClick = {
                                                if (replyKind == 1) kind1RepliesViewModel.setSortOrder(Kind1ReplySortOrder.MOST_LIKED)
                                                else threadRepliesViewModel.setSortOrder(ReplySortOrder.MOST_LIKED)
                                                showSortMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Empty state: no replies (no loader)
                    if (repliesState.replies.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No replies yet. Be the first to reply!",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Display replies — always use threaded path (wraps flat replies as level-0 when threadedReplies empty).
            // Avoids structural flip between flat/threaded itemsIndexed which corrupts LazyColumn draws.
            run {
                val displayThreaded = if (repliesState.threadedReplies.isNotEmpty()) {
                    repliesState.threadedReplies
                } else {
                    repliesState.replies.map { ThreadedReply(reply = it, children = emptyList(), level = 0) }
                }
                val displayList = when {
                    currentRootReplyId != null -> subtreeWithStructure(displayThreaded, currentRootReplyId!!) ?: displayThreaded
                    else -> displayThreaded
                }
                if (currentRootReplyId != null) {
                    item(key = "back_to_subthread") {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    rootReplyIdStack = rootReplyIdStack.dropLast(1)
                                },
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (rootReplyIdStack.size == 1) "Back to full thread" else "Back",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                itemsIndexed(
                    items = displayList,
                    key = { _, it -> logicalReplyKey(it.reply) }
                ) { index, threadedReply ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        ThreadedReplyCard(
                            threadedReply = threadedReply,
                            isLastRootReply = index == displayList.size - 1,
                            rootAuthorId = note.author.id,
                            commentStates = commentStates,
                            noteCountsByNoteId = noteCountsByNoteId,
                            onLike = { replyId ->
                                if (replyKind == 1) kind1RepliesViewModel.likeReply(replyId)
                                else threadRepliesViewModel.likeReply(replyId)
                            },
                            onReply = effectiveOnCommentReply,
                            onProfileClick = onProfileClick,
                            onRelayClick = onRelayNavigate,
                            shouldCloseZapMenus = shouldCloseZapMenus,
                            expandedZapMenuReplyId = expandedZapMenuCommentId,
                            onExpandZapMenu = { replyId ->
                                expandedZapMenuCommentId = if (expandedZapMenuCommentId == replyId) null else replyId
                            },
                            onZap = effectiveOnZap,
                            onZapSettings = { showZapConfigDialog = true },
                            expandedControlsReplyId = expandedControlsReplyId,
                            onExpandedControlsReplyChange = onExpandedControlsReplyChange,
                            expandedRelaysReplyId = expandedRelaysReplyId,
                            onRelaysExpandedReplyChange = { expandedRelaysReplyId = it },
                            onReadMoreReplies = { replyId -> rootReplyIdStack = rootReplyIdStack + replyId },
                            onImageTap = { urls, idx -> onImageTap(note, urls, idx) },
                            onVideoClick = onVideoClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (index < displayList.size - 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }

    // ✅ ZAP CONFIGURATION: Dialogs for editing zap amounts
    if (showZapConfigDialog) {
        com.example.views.ui.components.ZapConfigurationDialog(
            onDismiss = { showZapConfigDialog = false },
            onOpenWalletSettings = {
                showZapConfigDialog = false
                showWalletConnectDialog = true
            }
        )
    }

    if (showWalletConnectDialog) {
        com.example.views.ui.components.WalletConnectDialog(
            onDismiss = { showWalletConnectDialog = false }
        )
    }

    // Copy text dialog: raw note body in a popup; user can select/copy part or all
    if (showCopyTextDialog) {
        val scrollState = rememberScrollState()
        AlertDialog(
            onDismissRequest = { showCopyTextDialog = false },
            title = { Text("Note text") },
            text = {
                Box(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(scrollState)
                ) {
                    SelectionContainer {
                        Text(
                            text = copyTextContent.ifEmpty { " " },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setPrimaryClip(
                            android.content.ClipData.newPlainText("note", copyTextContent)
                        )
                        showCopyTextDialog = false
                    }
                ) {
                    Text("Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCopyTextDialog = false }) {
                    Text("Dismiss")
                }
            }
        )
    }

    // Kind-1111 reply dialog: publish thread reply (topic thread); show root note so author remembers context
    if (showReplyDialog && onPublishThreadReply != null) {
        var replyContent by remember { mutableStateOf("") }
        val parentPubkey = parentReplyId?.let { pid ->
            repliesState.replies.find { it.id == pid }?.author?.id
        }
        AlertDialog(
            onDismissRequest = { showReplyDialog = false },
            title = { Text(if (parentReplyId == null) "Reply to topic" else "Reply") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "Replying to",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ProfilePicture(
                                    author = note.author,
                                    size = 32.dp,
                                    onClick = { }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = note.author.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = note.content.take(200).let { if (note.content.length > 200) "$it…" else it },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3
                                    )
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = replyContent,
                        onValueChange = { replyContent = it },
                        label = { Text("Your reply") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (replyKind == 1111 && currentUserAuthor != null) {
                            threadRepliesViewModel.addOptimisticReply(
                                rootId = note.id,
                                parentId = parentReplyId,
                                content = replyContent,
                                currentUserAuthor = currentUserAuthor
                            )
                        }
                        val err = onPublishThreadReply(note.id, note.author.id, parentReplyId, parentPubkey, replyContent)
                        if (err != null) {
                            android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            showReplyDialog = false
                            replyContent = ""
                            android.widget.Toast.makeText(context, "Reply sent", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReplyDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ModernCommentThreadItem(
    commentThread: CommentThread,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    depth: Int,
    commentStates: MutableMap<String, CommentState>,
    expandedControlsCommentId: String?,
    onExpandControls: (String) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    isLastComment: Boolean = false,
    modifier: Modifier = Modifier
) {
    val commentId = commentThread.comment.id
    val state = commentStates.getOrPut(commentId) { CommentState() }
    val isControlsExpanded = expandedControlsCommentId == commentId

    // ✅ ULTRA COMPACT INDENTATION: Very tight spacing for child comments
    val indentPadding = (depth * 1.5).dp

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(androidx.compose.foundation.layout.IntrinsicSize.Min) // Critical for proper vertical lines
            .padding(start = indentPadding)
    ) {
        // Vertical thread line - like original but cleaner
        if (depth > 0) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight() // Full height for proper thread navigation
                    .background(ThreadLineColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Comment content - no individual animation to prevent staggering
        Column(
            modifier = Modifier
                .weight(1f)
        ) {
            ModernCommentCard(
                comment = commentThread.comment,
                onLike = onLike,
                onReply = onReply,
                onProfileClick = onProfileClick,
                onZap = onZap,
                onCustomZap = onCustomZap,
                onZapSettings = onZapSettings,
                isControlsExpanded = isControlsExpanded,
                onToggleControls = { onExpandControls(commentId) },
                isCollapsed = state.isCollapsed,
                onCollapsedChange = { collapsed ->
                    commentStates[commentId] = state.copy(
                        isCollapsed = collapsed,
                        isExpanded = !collapsed
                    )
                },
                // ✅ ZAP MENU AWARENESS: Pass zap menu state to ModernCommentCard
                shouldCloseZapMenus = shouldCloseZapMenus,
                expandedZapMenuCommentId = expandedZapMenuCommentId,
                onExpandZapMenu = { onExpandZapMenu(commentId) },
                modifier = Modifier.fillMaxWidth()
            )

            // Replies - all animated together
            if (state.isExpanded && !state.isCollapsed && commentThread.replies.isNotEmpty()) {
                commentThread.replies.forEachIndexed { index, reply ->
                    ModernCommentThreadItem(
                        commentThread = reply,
                        onLike = onLike,
                        onReply = onReply,
                        onProfileClick = onProfileClick,
                        onZap = onZap,
                        onCustomZap = onCustomZap,
                        onZapSettings = onZapSettings,
                        depth = depth + 1,
                        commentStates = commentStates,
                        expandedControlsCommentId = expandedControlsCommentId,
                        onExpandControls = onExpandControls,
                        // ✅ ZAP MENU AWARENESS: Pass zap menu state to nested replies
                        shouldCloseZapMenus = shouldCloseZapMenus,
                        expandedZapMenuCommentId = expandedZapMenuCommentId,
                        onExpandZapMenu = onExpandZapMenu,
                        isLastComment = index == commentThread.replies.size - 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Minimal separator for top-level comments (but not the last one)
            if (depth == 0 && !isLastComment) {
                HorizontalDivider(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernCommentCard(
    comment: Comment,
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onZap: (String, Long) -> Unit = { _, _ -> },
    onCustomZap: (String) -> Unit = {},
    onZapSettings: () -> Unit = {},
    isControlsExpanded: Boolean,
    onToggleControls: () -> Unit,
    isCollapsed: Boolean,
    onCollapsedChange: (Boolean) -> Unit,
    // ✅ ZAP MENU AWARENESS: Add zap menu state parameters
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuCommentId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val commentId = comment.id
    val isZapMenuExpanded = expandedZapMenuCommentId == commentId

    // ✅ ZAP MENU AWARENESS: Close zap menu when shouldCloseZapMenus is true (like feed cards)
    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus && isZapMenuExpanded) {
            onExpandZapMenu(commentId) // This will close the menu
        }
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (isCollapsed) {
                        onCollapsedChange(false)
                    } else {
                        onToggleControls()
                    }
                },
                onLongClick = {
                    if (!isCollapsed) {
                        onCollapsedChange(true)
                    }
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isCollapsed) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RectangleShape, // Sharp, edge-to-edge
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (!isCollapsed) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Modern author info with better spacing
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ProfilePicture(
                        author = comment.author,
                        size = 36.dp,
                        onClick = { onProfileClick(comment.author.id) }
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = comment.author.displayName,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatTimestamp(comment.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Content with better typography
                Text(
                    text = comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 22.sp
                )

                // Embedded media parsed from comment content
                val commentMediaUrls = remember(comment.content) {
                    com.example.views.utils.UrlDetector.findUrls(comment.content)
                        .filter { com.example.views.utils.UrlDetector.isImageUrl(it) || com.example.views.utils.UrlDetector.isVideoUrl(it) }
                        .distinct()
                }
                if (commentMediaUrls.isNotEmpty()) {
                    val commentImageUrls = commentMediaUrls.filter { com.example.views.utils.UrlDetector.isImageUrl(it) }
                    val commentVideoUrls = commentMediaUrls.filter { com.example.views.utils.UrlDetector.isVideoUrl(it) }
                    Spacer(modifier = Modifier.height(6.dp))
                    if (commentImageUrls.size == 1 && commentVideoUrls.isEmpty()) {
                        AsyncImage(
                            model = commentImageUrls[0],
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            commentImageUrls.take(3).forEach { url ->
                                AsyncImage(
                                    model = url,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                            }
                            commentVideoUrls.take(2).forEach { _ ->
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Video",
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Optimized controls - only show/hide, no complex animations
                if (isControlsExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerLow,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp)
                    ) {

                        // ✅ COMPACT CONTROLS: Right-aligned with consistent spacing
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactModernButton(
                                icon = Icons.Outlined.ArrowUpward,
                                contentDescription = "Upvote",
                                isActive = comment.isLiked,
                                onClick = { onLike(comment.id) }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.ArrowDownward,
                                contentDescription = "Downvote",
                                isActive = false,
                                onClick = { /* Handle downvote */ }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.Bookmark,
                                contentDescription = "Bookmark",
                                isActive = false,
                                onClick = { /* Handle bookmark */ }
                            )

                            CompactModernButton(
                                icon = Icons.Outlined.Reply,
                                contentDescription = "Reply",
                                isActive = false,
                                onClick = { onReply(comment.id) }
                            )

                            // Zap button with menu - using shared state (like feed cards)
                            CompactModernButton(
                                icon = Icons.Filled.Bolt,
                                contentDescription = "Zap",
                                isActive = false,
                                onClick = { onExpandZapMenu(commentId) }
                            )

                            // More options
                            var showMenu by remember { mutableStateOf(false) }
                            Box {
                                CompactModernButton(
                                    icon = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    isActive = false,
                                    onClick = { showMenu = true }
                                )

                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Share") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Report") },
                                        onClick = { showMenu = false },
                                        leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    }

                    // Zap menu - flowing layout with custom amounts, test, and edit buttons
                    AnimatedVisibility(
                        visible = isZapMenuExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Flowing zap amounts using FlowRow
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Get custom zap amounts from ZapAmountManager
                                val context = LocalContext.current
                                LaunchedEffect(Unit) {
                                    com.example.views.utils.ZapAmountManager.initialize(context)
                                }
                                val zapAmounts by com.example.views.utils.ZapAmountManager.zapAmounts.collectAsState()

                                // Zap amount chips - sorted largest to smallest
                                zapAmounts.sortedDescending().forEach { amount ->
                                    FilterChip(
                                        selected = amount == 1L, // Highlight 1 sat
                                        onClick = {
                                            onExpandZapMenu(commentId) // Close menu using shared state
                                            onZap(comment.id, amount)
                                        },
                                        label = {
                                            Text(
                                                text = com.example.views.utils.ZapUtils.formatZapAmount(amount),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Bolt,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFFA500),
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White,
                                            containerColor = Color(0xFFFFA500),
                                            labelColor = Color.White,
                                            iconColor = Color.White
                                        ),
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = Color(0xFFFFA500)
                                        )
                                    )
                                }

                                // Edit zap amounts chip
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onExpandZapMenu(commentId) // Close menu using shared state
                                        onZapSettings()
                                    },
                                    label = { Text("Edit") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = "Edit Zap Amounts",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Compact collapsed state
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Expand thread",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = comment.author.displayName,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onProfileClick(comment.author.id) }
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "· tap to expand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ModernActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ✅ CONSISTENT: Match main card ActionButton pattern
    Box(
        modifier = modifier
            .height(48.dp)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp) // ✅ CONSISTENT: Match main card icon size
        )
    }
}

@Composable
private fun CompactModernButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint ?: if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun formatReplyTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> "${diff / 86400_000}d ago"
    }
}

/**
 * Threaded reply card: thin line along the left edge of the card, stacking horizontally per level
 * for coherent conversation view. Condensed layout.
 */
@Composable
private fun ThreadedReplyCard(
    threadedReply: ThreadedReply,
    isLastRootReply: Boolean = true,
    /** Root note author id; when reply.author matches, show OP highlight and "OP" label. */
    rootAuthorId: String? = null,
    commentStates: MutableMap<String, CommentState>,
    /** Counts (reactions, zaps, replies) per note ID from NoteCountsRepository. */
    noteCountsByNoteId: Map<String, com.example.views.repository.NoteCounts> = emptyMap(),
    onLike: (String) -> Unit,
    onReply: (String) -> Unit,
    onProfileClick: (String) -> Unit,
    onRelayClick: (String) -> Unit = {},
    shouldCloseZapMenus: Boolean = false,
    expandedZapMenuReplyId: String? = null,
    onExpandZapMenu: (String) -> Unit = {},
    onZap: (String, Long) -> Unit = { _, _ -> },
    onZapSettings: () -> Unit = {},
    /** Which reply ID has controls expanded; null = all compact. */
    expandedControlsReplyId: String? = null,
    onExpandedControlsReplyChange: (String?) -> Unit = {},
    /** Which reply ID has "View relays" row expanded; toggled from dropdown. */
    expandedRelaysReplyId: String? = null,
    onRelaysExpandedReplyChange: (String?) -> Unit = {},
    /** When level >= MAX_THREAD_DEPTH and there are children, tap "Read N more replies" opens sub-thread. */
    onReadMoreReplies: (String) -> Unit = {},
    onImageTap: (List<String>, Int) -> Unit = { _, _ -> },
    onVideoClick: (List<String>, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val reply = threadedReply.reply
    val replyKey = logicalReplyKey(reply)
    val isControlsExpanded = expandedControlsReplyId == reply.id
    val isRelaysExpanded = expandedRelaysReplyId == reply.id
    val onToggleControls: () -> Unit = { onExpandedControlsReplyChange(if (expandedControlsReplyId == reply.id) null else reply.id) }
    val level = threadedReply.level
    val state = commentStates.getOrPut(replyKey) { CommentState() }
    val canCollapse = true // allow collapsing single/leaf replies as well as branches
    val threadLineWidth = 2.dp
    val indentPerLevel = 1.dp
    val isZapMenuExpanded = expandedZapMenuReplyId == reply.id

    // Resolve author from profile cache so display name/avatar update when profiles load
    val profileCache = com.example.views.repository.ProfileMetadataCache.getInstance()
    val diskCacheReady by profileCache.diskCacheRestored.collectAsState()
    val authorPubkey = remember(reply.author.id) { com.example.views.utils.normalizeAuthorIdForCache(reply.author.id) }
    var profileRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(authorPubkey) {
        profileCache.profileUpdated
            .filter { it == authorPubkey }
            .collect { profileRevision++ }
    }
    val displayAuthor = remember(reply.author.id, profileRevision, diskCacheReady) {
        profileCache.resolveAuthor(reply.author.id)
    }

    LaunchedEffect(shouldCloseZapMenus) {
        if (shouldCloseZapMenus && isZapMenuExpanded) onExpandZapMenu(reply.id)
    }

    // Fixed gutter so content never sprawls: all cards start at the same horizontal position.
    // Line position still varies by level (0, 1, 2, 3, 4 dp) so depth is visible.
    val fixedGutter = indentPerLevel * MAX_THREAD_DEPTH + threadLineWidth
    val lineX = indentPerLevel * level
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Spacer(modifier = Modifier.width(fixedGutter))
            Column(modifier = Modifier.weight(1f)) {
        // Show "event not found" indicator for orphan replies whose parent wasn't fetched
        if (threadedReply.isOrphan) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LinkOff,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = "Replying to an event not found",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (state.isCollapsed) {
                            commentStates[replyKey] = state.copy(isCollapsed = false, isExpanded = true)
                        } else {
                            onToggleControls()
                        }
                    },
                    onLongClick = {
                        if (canCollapse && !state.isCollapsed) {
                            commentStates[replyKey] = state.copy(isCollapsed = true, isExpanded = false)
                        }
                    }
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RectangleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            if (state.isCollapsed) {
                val childCount = threadedReply.totalReplies
                val label = when {
                    childCount == 0 -> "1 reply"
                    childCount == 1 -> "view 1 more reply"
                    else -> "view $childCount more replies"
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "[+]",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ProfilePicture(
                                author = displayAuthor,
                                size = 28.dp,
                                onClick = { onProfileClick(reply.author.id) }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                                        val isOp = rootAuthorId != null && com.example.views.utils.normalizeAuthorIdForCache(reply.author.id) == com.example.views.utils.normalizeAuthorIdForCache(rootAuthorId)
                                        if (isOp) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Surface(
                                                    color = Color(0xFF8E30EB),
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(
                                                        text = displayAuthor.displayName,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
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
                                                text = displayAuthor.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    val headerCounts = noteCountsByNoteId[reply.id]
                                    val hcReactions = headerCounts?.reactions ?: emptyList()
                                    val hcZapSats = headerCounts?.zapTotalSats ?: 0L
                                    val hcEmojiUrls = headerCounts?.customEmojiUrls ?: emptyMap()
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (hcReactions.isNotEmpty()) {
                                            val uniqueEmojis = hcReactions.distinct().take(3)
                                            uniqueEmojis.forEach { emoji ->
                                                com.example.views.ui.components.ReactionEmoji(
                                                    emoji = emoji,
                                                    customEmojiUrls = hcEmojiUrls,
                                                    fontSize = 12.sp,
                                                    imageSize = 14.dp
                                                )
                                            }
                                            if (hcReactions.size > 1) {
                                                Text(
                                                    text = "${hcReactions.size}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFFE57373)
                                                )
                                            }
                                        }
                                        if (hcZapSats > 0) {
                                            Text(
                                                text = "⚡${com.example.views.utils.ZapUtils.formatZapAmount(hcZapSats)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFFD700)
                                            )
                                        }
                                        Text(
                                            text = formatReplyTimestamp(reply.timestamp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                        // 3-dot menu in header
                                        var showMore by remember { mutableStateOf(false) }
                                        Box {
                                            Icon(
                                                imageVector = Icons.Default.MoreVert,
                                                contentDescription = "More",
                                                modifier = Modifier
                                                    .size(18.dp)
                                                    .clickable(
                                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                                        indication = null
                                                    ) { showMore = true },
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            DropdownMenu(
                                                expanded = showMore,
                                                onDismissRequest = { showMore = false }
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("View relays") },
                                                    onClick = {
                                                        onRelaysExpandedReplyChange(if (isRelaysExpanded) null else reply.id)
                                                        showMore = false
                                                    },
                                                    leadingIcon = { Icon(Icons.Default.Public, contentDescription = null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Share") },
                                                    onClick = { showMore = false },
                                                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                                                )
                                                DropdownMenuItem(
                                                    text = { Text("Report") },
                                                    onClick = { showMore = false },
                                                    leadingIcon = { Icon(Icons.Default.Report, contentDescription = null) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            val replyRelayUrls = reply.relayUrls.distinct().take(6)
                            if (replyRelayUrls.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                RelayOrbs(relayUrls = replyRelayUrls, onRelayClick = onRelayClick)
                            }
                        }

                        if (isRelaysExpanded && reply.relayUrls.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                RelayOrbs(
                                    relayUrls = reply.relayUrls.distinct().take(8),
                                    onRelayClick = onRelayClick
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = reply.content,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )

                        // Embedded media (images/videos) from reply
                        if (reply.mediaUrls.isNotEmpty()) {
                            val imageUrls = reply.mediaUrls.filter {
                                com.example.views.utils.UrlDetector.isImageUrl(it)
                            }
                            val videoUrls = reply.mediaUrls.filter {
                                com.example.views.utils.UrlDetector.isVideoUrl(it)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (imageUrls.size == 1 && videoUrls.isEmpty()) {
                                // Single image — aspect-ratio-aware
                                val imgUrl = imageUrls[0]
                                val cachedRatio = com.example.views.utils.MediaAspectRatioCache.get(imgUrl)
                                val imgModifier = if (cachedRatio != null) {
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(cachedRatio.coerceIn(0.5f, 3.0f))
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageTap(reply.mediaUrls, 0) }
                                } else {
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 240.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onImageTap(reply.mediaUrls, 0) }
                                }
                                AsyncImage(
                                    model = imgUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = imgModifier,
                                    onSuccess = { state ->
                                        val drawable = state.result.drawable
                                        com.example.views.utils.MediaAspectRatioCache.add(imgUrl, drawable.intrinsicWidth, drawable.intrinsicHeight)
                                    }
                                )
                            } else {
                                // Multiple media — thumbnail row
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    imageUrls.take(3).forEachIndexed { idx, url ->
                                        AsyncImage(
                                            model = url,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .clickable { onImageTap(reply.mediaUrls, idx) }
                                        )
                                    }
                                    videoUrls.take(2).forEachIndexed { idx, _ ->
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { onVideoClick(reply.mediaUrls, imageUrls.size + idx) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Video",
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Reactions and zaps are now shown in the header row

                        if (isControlsExpanded) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Row(
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                                    )
                                    .padding(vertical = 2.dp, horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CompactModernButton(
                                    icon = Icons.Outlined.ArrowUpward,
                                    contentDescription = "Upvote",
                                    isActive = false,
                                    onClick = { /* placeholder for future voting */ }
                                )
                                CompactModernButton(
                                    icon = Icons.Outlined.ArrowDownward,
                                    contentDescription = "Downvote",
                                    isActive = false,
                                    onClick = { /* placeholder for future voting */ }
                                )
                                CompactModernButton(
                                    icon = if (reply.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Like",
                                    isActive = reply.isLiked,
                                    onClick = { onLike(reply.id) },
                                    tint = if (reply.isLiked) Color.Red else null
                                )
                                CompactModernButton(
                                    icon = Icons.Outlined.Reply,
                                    contentDescription = "Reply",
                                    isActive = false,
                                    onClick = { onReply(reply.id) }
                                )
                                CompactModernButton(
                                    icon = Icons.Filled.Bolt,
                                    contentDescription = "Zap",
                                    isActive = false,
                                    onClick = { onExpandZapMenu(reply.id) }
                                )
                            }
                        }
                        }
                    }

                }
                    if (isControlsExpanded) {
                    AnimatedVisibility(
                        visible = isZapMenuExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp, horizontal = 12.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val context = LocalContext.current
                                LaunchedEffect(Unit) {
                                    com.example.views.utils.ZapAmountManager.initialize(context)
                                }
                                val zapAmounts by com.example.views.utils.ZapAmountManager.zapAmounts.collectAsState()
                                zapAmounts.sortedDescending().forEach { amount ->
                                    FilterChip(
                                        selected = amount == 1L,
                                        onClick = {
                                            onExpandZapMenu(reply.id)
                                            onZap(reply.id, amount)
                                        },
                                        label = {
                                            Text(
                                                text = com.example.views.utils.ZapUtils.formatZapAmount(amount),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Filled.Bolt,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFFFFA500),
                                            selectedLabelColor = Color.White,
                                            selectedLeadingIconColor = Color.White,
                                            containerColor = Color(0xFFFFA500),
                                            labelColor = Color.White,
                                            iconColor = Color.White
                                        ),
                                        border = BorderStroke(1.dp, Color(0xFFFFA500))
                                    )
                                }
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        onExpandZapMenu(reply.id)
                                        onZapSettings()
                                    },
                                    label = { Text("Edit") },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Filled.Edit,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                        }
                    }
                }
            }
                    }
        }
        if (!state.isCollapsed) {
            if (level >= MAX_THREAD_DEPTH && threadedReply.children.isNotEmpty()) {
                val n = threadedReply.totalReplies
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onReadMoreReplies(reply.id) },
                    color = MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Read $n more replies",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            } else {
                threadedReply.children.forEach { childReply ->
                    ThreadedReplyCard(
                        threadedReply = childReply,
                        isLastRootReply = true,
                        rootAuthorId = rootAuthorId,
                        commentStates = commentStates,
                        noteCountsByNoteId = noteCountsByNoteId,
                        onLike = onLike,
                        onReply = onReply,
                        onProfileClick = onProfileClick,
                        onRelayClick = onRelayClick,
                        shouldCloseZapMenus = shouldCloseZapMenus,
                        expandedZapMenuReplyId = expandedZapMenuReplyId,
                        onExpandZapMenu = onExpandZapMenu,
                        onZap = onZap,
                        onZapSettings = onZapSettings,
                        expandedControlsReplyId = expandedControlsReplyId,
                        onExpandedControlsReplyChange = onExpandedControlsReplyChange,
                        expandedRelaysReplyId = expandedRelaysReplyId,
                        onRelaysExpandedReplyChange = onRelaysExpandedReplyChange,
                        onReadMoreReplies = onReadMoreReplies,
                        onImageTap = onImageTap,
                        onVideoClick = onVideoClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        }
        }
        // Thread line: full height of this block (card + children)
        Box(
            modifier = Modifier
                .matchParentSize()
                .align(Alignment.CenterStart)
        ) {
            Box(
                modifier = Modifier
                    .width(threadLineWidth)
                    .offset(x = lineX)
                    .fillMaxHeight()
                    .background(ThreadLineColor, RectangleShape)
            )
        }
    }
}
// Data classes are imported from ThreadViewScreen.kt

// formatTimestamp is imported from ThreadViewScreen.kt

@Preview(showBackground = true)
@Composable
fun ModernThreadViewScreenPreview() {
    MaterialTheme {
        ModernThreadViewScreen(
            note = SampleData.sampleNotes.first(),
            comments = createSampleCommentThreads()
        )
    }
}
