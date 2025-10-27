package com.example.views.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.views.data.UrlPreviewInfo

/**
 * Card component for displaying URL previews
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UrlPreviewCard(
    previewInfo: UrlPreviewInfo,
    modifier: Modifier = Modifier,
    onUrlClick: (String) -> Unit = {},
    onUrlLongClick: (String) -> Unit = {}
) {
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { 
                    onUrlClick(previewInfo.url)
                    uriHandler.openUri(previewInfo.url)
                },
                onLongClick = { 
                    showMenu = true
                    onUrlLongClick(previewInfo.url)
                }
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            // Image preview
            if (previewInfo.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = previewInfo.imageUrlFullPath,
                    contentDescription = previewInfo.title.ifEmpty { "Preview image" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                    contentScale = ContentScale.Crop
                )
            }
            
            // Content
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Site name
                if (previewInfo.siteName.isNotEmpty()) {
                    Text(
                        text = previewInfo.siteName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // Title
                if (previewInfo.title.isNotEmpty()) {
                    Text(
                        text = previewInfo.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                // Description
                if (previewInfo.description.isNotEmpty()) {
                    Text(
                        text = previewInfo.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // URL and actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = previewInfo.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    IconButton(
                        onClick = { 
                            clipboardManager.setText(AnnotatedString(previewInfo.url))
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy URL",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
    
    // Context menu
    if (showMenu) {
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text("URL Options") },
            text = { Text(previewInfo.url) },
            confirmButton = {
                TextButton(
                    onClick = {
                        uriHandler.openUri(previewInfo.url)
                        showMenu = false
                    }
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(previewInfo.url))
                        showMenu = false
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy")
                }
            }
        )
    }
}

@Preview(showBackground = true)
@Composable
fun UrlPreviewCardPreview() {
    MaterialTheme {
        UrlPreviewCard(
            previewInfo = UrlPreviewInfo(
                url = "https://example.com",
                title = "Example Website",
                description = "This is an example website with a long description that should be truncated when it exceeds the maximum number of lines.",
                imageUrl = "https://picsum.photos/400/200",
                siteName = "example.com"
            )
        )
    }
}
