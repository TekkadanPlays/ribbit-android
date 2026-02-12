package com.example.views.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextStyle
import com.halilibo.richtext.commonmark.CommonmarkAstNodeParser
import com.halilibo.richtext.markdown.BasicMarkdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText

/**
 * Heuristic to detect whether note content contains markdown formatting.
 * Mirrors Amethyst's isMarkdown() check.
 */
fun isMarkdown(content: String): Boolean =
    content.startsWith("> ") ||
        content.startsWith("# ") ||
        content.contains("##") ||
        content.contains("__") ||
        content.contains("**") ||
        content.contains("```") ||
        content.contains("](")

/**
 * Renders note content as parsed Markdown using compose-richtext.
 *
 * Handles clickable links (URLs, nostr: URIs, hashtags) via a custom UriHandler
 * that delegates to the provided callbacks before falling back to the system handler.
 *
 * For non-markdown content, callers should continue using [ClickableNoteContent].
 */
@Composable
fun MarkdownNoteContent(
    content: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    onProfileClick: (String) -> Unit = {},
    onNoteClick: () -> Unit = {},
    onUrlClick: (String) -> Unit = {},
    onHashtagClick: (String) -> Unit = {}
) {
    val systemUriHandler = LocalUriHandler.current

    val uriHandler = remember(systemUriHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                when {
                    // nostr: profile references
                    uri.startsWith("nostr:npub1") || uri.startsWith("nostr:nprofile1") -> {
                        // Extract hex from the URI — delegate to profile click
                        try {
                            val parsed = com.vitorpamplona.quartz.nip19Bech32.Nip19Parser.uriToRoute(uri)
                            val hex = when (val entity = parsed?.entity) {
                                is com.vitorpamplona.quartz.nip19Bech32.entities.NPub -> entity.hex
                                is com.vitorpamplona.quartz.nip19Bech32.entities.NProfile -> entity.hex
                                else -> null
                            }
                            if (hex != null) {
                                onProfileClick(hex)
                                return
                            }
                        } catch (_: Exception) { }
                        runCatching { systemUriHandler.openUri(uri) }
                    }
                    // nostr: note/event references
                    uri.startsWith("nostr:note1") || uri.startsWith("nostr:nevent1") -> {
                        onNoteClick()
                    }
                    // Hashtag links (from markdown [#tag](nostr:hashtag?id=tag))
                    uri.startsWith("nostr:hashtag") -> {
                        val tag = uri.substringAfter("id=", "")
                        if (tag.isNotEmpty()) onHashtagClick(tag)
                    }
                    // Regular HTTP(S) URLs
                    uri.startsWith("http://") || uri.startsWith("https://") -> {
                        onUrlClick(uri)
                    }
                    // Fallback
                    else -> runCatching { systemUriHandler.openUri(uri) }
                }
            }
        }
    }

    val astNode = remember(content) {
        CommonmarkAstNodeParser().parse(content)
    }

    ProvideTextStyle(style) {
        CompositionLocalProvider(LocalUriHandler provides uriHandler) {
            Column(modifier = modifier) {
                RichText(
                    style = RichTextStyle()
                ) {
                    BasicMarkdown(astNode)
                }
            }
        }
    }
}
