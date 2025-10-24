# Zap Integration Quick Guide

A practical guide for integrating zap functionality into your Ribbit screens and components.

## Quick Start

### 1. Import Required Components

```kotlin
import com.example.views.ui.components.ZapButton
import com.example.views.ui.components.CompactZapButton
import com.example.views.utils.ZapUtils
```

### 2. Add Zap Button to Your Composable

**Simple Integration:**
```kotlin
@Composable
fun MyNoteCard(note: Note) {
    Card {
        // Your note content...
        
        Row {
            // Other action buttons...
            
            ZapButton(
                isZapped = false,
                zapCount = note.zapCount,
                totalZappedAmount = note.totalZappedSats,
                onZap = { amount, comment ->
                    // Handle the zap
                    handleZap(note.id, amount, comment)
                }
            )
        }
    }
}
```

**With Wallet Connection Check:**
```kotlin
@Composable
fun MyNoteCard(
    note: Note,
    isWalletConnected: Boolean
) {
    Card {
        // Your note content...
        
        Row {
            ZapButton(
                isZapped = note.isZappedByUser,
                zapCount = note.zapCount,
                totalZappedAmount = note.totalZappedSats,
                onZap = { amount, comment ->
                    if (isWalletConnected) {
                        viewModel.sendZap(note, amount, comment)
                    } else {
                        // Prompt user to connect wallet
                        showWalletSetupDialog()
                    }
                },
                enabled = isWalletConnected
            )
        }
    }
}
```

## Component Variants

### Standard ZapButton

Use for note cards, posts, and main content areas.

```kotlin
ZapButton(
    isZapped: Boolean = false,           // Has current user zapped?
    zapCount: Int = 0,                   // Number of zaps received
    totalZappedAmount: Long = 0,         // Total sats zapped
    onZap: (Long, String) -> Unit,       // Callback: (amount, comment)
    modifier: Modifier = Modifier,
    enabled: Boolean = true,             // Enable/disable button
    showCount: Boolean = true,           // Show zap count/amount
    iconSize: Int = 20                   // Icon size in dp
)
```

**Example:**
```kotlin
ZapButton(
    isZapped = note.zappers.contains(currentUserId),
    zapCount = note.zaps.size,
    totalZappedAmount = note.zaps.sumOf { it.amount },
    onZap = { amount, comment ->
        viewModel.zapNote(note.id, amount, comment)
    },
    enabled = walletViewModel.isConnected(),
    showCount = true,
    iconSize = 20
)
```

### CompactZapButton

Use in toolbars, compact layouts, or when space is limited.

```kotlin
CompactZapButton(
    isZapped: Boolean = false,
    totalZappedAmount: Long = 0,
    onZap: (Long, String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
)
```

**Example:**
```kotlin
Row(
    modifier = Modifier.height(40.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp)
) {
    CompactZapButton(
        isZapped = note.isZapped,
        totalZappedAmount = note.totalSats,
        onZap = { amount, comment ->
            viewModel.zapNote(note.id, amount, comment)
        }
    )
    // Other compact buttons...
}
```

## Using ZapUtils

### Format Display Amounts

```kotlin
// Compact format for UI
val displayAmount = ZapUtils.formatZapAmount(5000L)
// Result: "5.0K"

// Full format with label
val fullAmount = ZapUtils.formatSats(5000L)
// Result: "5K sats"

// Large amounts
ZapUtils.formatSats(1_500_000L)
// Result: "1.5 M sats"

ZapUtils.formatSats(100_000_000L)
// Result: "1.00 BTC"
```

### Validate Amounts

```kotlin
fun validateZapAmount(amount: Long): Boolean {
    return amount.isValidZapAmount() // Extension function
}

// Or directly
if (amount > 0 && amount <= 21_000_000_000L) {
    // Valid amount
}
```

### Convert Units

```kotlin
// Sats to millisats (for Lightning invoices)
val millisats = 1000L.toMillisats()
// Result: 1_000_000

// Millisats to sats
val sats = 1_000_000L.toSats()
// Result: 1000
```

### Validate NWC URIs

```kotlin
fun validateWalletUri(uri: String): Boolean {
    return ZapUtils.isValidNwcUri(uri)
}

// Parse wallet name from URI
val walletName = ZapUtils.parseWalletName(nwcUri)
// Result: "Alby" or null
```

## Complete Integration Example

Here's a complete example showing all the pieces together:

```kotlin
@Composable
fun PostCard(
    post: Post,
    viewModel: PostViewModel,
    walletViewModel: WalletViewModel
) {
    val isWalletConnected by walletViewModel.isConnected.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Author info
            AuthorRow(post.author)
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Post content
            Text(text = post.content)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Reply button
                ReplyButton(
                    replyCount = post.replies.size,
                    onClick = { viewModel.showReplyDialog(post.id) }
                )
                
                // Repost button
                RepostButton(
                    isReposted = post.reposters.contains(currentUserId),
                    repostCount = post.reposts.size,
                    onClick = { viewModel.repost(post.id) }
                )
                
                // Zap button
                ZapButton(
                    isZapped = post.zappers.contains(currentUserId),
                    zapCount = post.zaps.size,
                    totalZappedAmount = post.totalZappedSats,
                    onZap = { amount, comment ->
                        if (isWalletConnected) {
                            viewModel.zapPost(post.id, amount, comment)
                        } else {
                            viewModel.showWalletSetupPrompt()
                        }
                    },
                    enabled = isWalletConnected
                )
                
                // Like button
                LikeButton(
                    isLiked = post.likers.contains(currentUserId),
                    likeCount = post.likes.size,
                    onClick = { viewModel.toggleLike(post.id) }
                )
            }
            
            // Show zap amount if post is zapped
            if (post.totalZappedSats > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚡ ${ZapUtils.formatSats(post.totalZappedSats)} total",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFA500)
                )
            }
        }
    }
}
```

## ViewModel Integration

### Basic Zap Handler

```kotlin
class PostViewModel : ViewModel() {
    
    private val _zapState = MutableStateFlow<ZapState>(ZapState.Idle)
    val zapState: StateFlow<ZapState> = _zapState.asStateFlow()
    
    fun zapPost(postId: String, amount: Long, comment: String) {
        viewModelScope.launch {
            try {
                _zapState.value = ZapState.Processing
                
                // Create zap request
                val zapAction = ZapUtils.createZapRequest(
                    recipientPubkey = post.authorPubkey,
                    amount = amount,
                    relays = post.relays,
                    comment = comment,
                    zapType = ZapUtils.ZapType.PUBLIC
                )
                
                // Send to NWC
                val result = walletService.sendZap(zapAction)
                
                if (result.isSuccess) {
                    _zapState.value = ZapState.Success
                    updatePostZapCount(postId, amount)
                } else {
                    _zapState.value = ZapState.Error(result.error)
                }
            } catch (e: Exception) {
                _zapState.value = ZapState.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    private fun updatePostZapCount(postId: String, amount: Long) {
        // Update local state
        // Broadcast zap event
    }
}

sealed class ZapState {
    object Idle : ZapState()
    object Processing : ZapState()
    object Success : ZapState()
    data class Error(val message: String) : ZapState()
}
```

### Advanced Zap Handler with Confirmation

```kotlin
class ZapViewModel : ViewModel() {
    
    private val _requireConfirmation = MutableStateFlow(true)
    private val confirmationThreshold = 10_000L // 10K sats
    
    suspend fun zapNote(
        noteId: String,
        amount: Long,
        comment: String,
        skipConfirmation: Boolean = false
    ) {
        // Check if confirmation needed
        if (!skipConfirmation && 
            amount >= confirmationThreshold && 
            _requireConfirmation.value) {
            // Show confirmation dialog
            showConfirmationDialog(noteId, amount, comment)
            return
        }
        
        // Validate amount
        if (!amount.isValidZapAmount()) {
            emitError("Invalid zap amount")
            return
        }
        
        // Check wallet connection
        if (!walletViewModel.isConnected.value) {
            emitError("Wallet not connected")
            return
        }
        
        // Process zap
        processZap(noteId, amount, comment)
    }
    
    private suspend fun processZap(
        noteId: String,
        amount: Long,
        comment: String
    ) {
        try {
            // Create and sign zap event
            val zapEvent = createZapEvent(noteId, amount, comment)
            
            // Send payment via NWC
            val paymentResult = nwcService.payInvoice(zapEvent.invoice)
            
            if (paymentResult.isSuccess) {
                // Broadcast zap event to relays
                broadcastZapEvent(zapEvent)
                
                // Update local cache
                updateNoteZapState(noteId, amount)
                
                emitSuccess("Zapped ${ZapUtils.formatSats(amount)}!")
            } else {
                emitError("Payment failed: ${paymentResult.error}")
            }
        } catch (e: Exception) {
            emitError("Error sending zap: ${e.message}")
        }
    }
}
```

## State Management Patterns

### Using Remember for Local State

```kotlin
@Composable
fun NoteWithZaps(note: Note) {
    var isZapped by remember { mutableStateOf(note.isZappedByUser) }
    var zapCount by remember { mutableStateOf(note.zapCount) }
    var totalAmount by remember { mutableStateOf(note.totalZappedSats) }
    
    ZapButton(
        isZapped = isZapped,
        zapCount = zapCount,
        totalZappedAmount = totalAmount,
        onZap = { amount, comment ->
            viewModel.zapNote(note.id, amount, comment)
            
            // Optimistic update
            isZapped = true
            zapCount += 1
            totalAmount += amount
        }
    )
}
```

### Using StateFlow for Global State

```kotlin
@Composable
fun NoteWithZaps(
    note: Note,
    viewModel: NoteViewModel
) {
    val zapState by viewModel.getZapState(note.id).collectAsState()
    
    ZapButton(
        isZapped = zapState.isZappedByUser,
        zapCount = zapState.count,
        totalZappedAmount = zapState.totalAmount,
        onZap = { amount, comment ->
            viewModel.zapNote(note.id, amount, comment)
        }
    )
}
```

## Custom Zap Amounts

### Setting Custom Defaults

```kotlin
object MyZapConfig {
    val customZapAmounts = listOf(
        50L,    // Custom micro-tip
        250L,   // Small tip
        1_000L, // Standard
        5_000L, // Generous
        21_000L // Bitcoin block reward reference
    )
}

// Use in your app
fun updateZapDefaults() {
    ZapUtils.DEFAULT_ZAP_AMOUNTS = MyZapConfig.customZapAmounts
}
```

## Accessibility

### Adding Content Descriptions

```kotlin
ZapButton(
    // ... other params ...
    modifier = Modifier.semantics {
        contentDescription = if (isZapped) {
            "Zapped with ${ZapUtils.formatSats(totalZappedAmount)}"
        } else {
            "Zap this post with Lightning"
        }
        role = Role.Button
    }
)
```

## Testing

### Unit Test Example

```kotlin
@Test
fun `test zap amount formatting`() {
    assertEquals("21", ZapUtils.formatZapAmount(21L))
    assertEquals("1.0K", ZapUtils.formatZapAmount(1000L))
    assertEquals("5.0K", ZapUtils.formatZapAmount(5000L))
    assertEquals("1.0M", ZapUtils.formatZapAmount(1_000_000L))
}

@Test
fun `test zap amount validation`() {
    assertTrue(100L.isValidZapAmount())
    assertFalse(0L.isValidZapAmount())
    assertFalse((-100L).isValidZapAmount())
}

@Test
fun `test NWC URI validation`() {
    val validUri = "nostr+walletconnect://pubkey?relay=wss://relay.test&secret=abc123"
    assertTrue(ZapUtils.isValidNwcUri(validUri))
    
    val invalidUri = "https://example.com"
    assertFalse(ZapUtils.isValidNwcUri(invalidUri))
}
```

### UI Test Example

```kotlin
@Test
fun testZapButtonInteraction() {
    composeTestRule.setContent {
        var zapped by remember { mutableStateOf(false) }
        
        ZapButton(
            isZapped = zapped,
            zapCount = 5,
            totalZappedAmount = 5000L,
            onZap = { _, _ -> zapped = true }
        )
    }
    
    // Click button
    composeTestRule.onNodeWithContentDescription("Zap").performClick()
    
    // Verify dialog appears
    composeTestRule.onNodeWithText("⚡ Zap").assertExists()
    
    // Select amount
    composeTestRule.onNodeWithText("⚡ 1.0K").performClick()
    
    // Confirm zap
    composeTestRule.onNodeWithText("Zap 1.0K").performClick()
    
    // Verify state change
    composeTestRule.onNodeWithContentDescription("Zap")
        .assertExists()
}
```

## Troubleshooting

### Common Issues

**Issue: Zap button not responding**
```kotlin
// Check wallet connection
if (!walletViewModel.isConnected.value) {
    // Prompt user to connect wallet
    navController.navigate("settings/account_preferences")
}
```

**Issue: Amounts not formatting correctly**
```kotlin
// Ensure you're using Long, not Int
val amount: Long = 1000L // ✓ Correct
val amount: Int = 1000    // ✗ Wrong type
```

**Issue: Dialog not dismissing**
```kotlin
var showDialog by remember { mutableStateOf(false) }

ZapButton(
    onZap = { amount, comment ->
        handleZap(amount, comment)
        showDialog = false // Ensure state updates
    }
)
```

## Best Practices

1. **Always check wallet connection** before enabling zap buttons
2. **Use optimistic updates** for better UX
3. **Handle errors gracefully** with user-friendly messages
4. **Provide visual feedback** during zap processing
5. **Cache zap states** to avoid unnecessary recomposition
6. **Use StateFlow** for reactive zap state updates
7. **Add confirmation** for large zap amounts
8. **Show zap receipts** when available
9. **Respect user privacy** for private zaps
10. **Test on multiple screen sizes** and orientations

## Resources

- Full Documentation: `WALLET_CONNECT_SETUP.md`
- Implementation Details: `WALLET_ZAP_IMPLEMENTATION.md`
- NIP-57 Spec: https://github.com/nostr-protocol/nips/blob/master/57.md
- NIP-47 Spec: https://github.com/nostr-protocol/nips/blob/master/47.md

## Support

For questions or issues:
- Check existing code in `ZapButton.kt` and `ZapUtils.kt`
- Open an issue with `zaps` label on GitHub
- Reference Amethyst implementation for advanced patterns

---

**Quick Reference Card**

```kotlin
// Minimal integration
ZapButton(
    isZapped = note.isZapped,
    zapCount = note.zaps.size,
    totalZappedAmount = note.totalSats,
    onZap = { amount, comment ->
        viewModel.zap(note, amount, comment)
    },
    enabled = walletConnected
)

// Format amounts
ZapUtils.formatZapAmount(1000L) // "1.0K"

// Validate
amount.isValidZapAmount() // Boolean
```
