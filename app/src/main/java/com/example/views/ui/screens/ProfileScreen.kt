package com.example.views.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.views.data.Author
import com.example.views.data.Note
import com.example.views.data.SampleData
import com.example.views.ui.components.ModernSearchBar
import com.example.views.ui.components.NoteCard

@Composable
fun ProfileScreen(
    author: Author,
    authorNotes: List<Note>,
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit = {},
    onLike: (String) -> Unit = {},
    onShare: (String) -> Unit = {},
    onComment: (String) -> Unit = {},
    onProfileClick: (String) -> Unit = {},
    onNavigateTo: (String) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    // Use predictive back for smooth gesture navigation
    androidx.activity.compose.BackHandler {
        onBackClick()
    }
    
    Scaffold(
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(top = 8.dp)
        ) {
            // Profile Header
            item {
                ProfileHeader(
                    author = author,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Notes List
            items(authorNotes.size) { index ->
                val note = authorNotes[index]
                NoteCard(
                    note = note,
                    onLike = onLike,
                    onShare = onShare,
                    onComment = onComment,
                    onProfileClick = onProfileClick,
                    onNoteClick = onNoteClick,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    author: Author,
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
            // Profile Picture with shared element key
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = author.displayName.take(1).uppercase(),
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                )
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
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Stats Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Notes",
                    value = "42", // TODO: Get actual count
                    onClick = { /* TODO: Navigate to notes */ }
                )
                StatItem(
                    label = "Followers",
                    value = "1.2K",
                    onClick = { /* TODO: Navigate to followers */ }
                )
                StatItem(
                    label = "Following",
                    value = "89",
                    onClick = { /* TODO: Navigate to following */ }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { /* TODO: Implement follow */ },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Follow")
                }
                
                OutlinedButton(
                    onClick = { /* TODO: Implement message */ },
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