package com.example.views.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.example.views.data.UrlPreviewInfo
import com.example.views.repository.ProfileMetadataCache
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub

private val npubPattern = Regex(
    "(nostr:)?@?(npub1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)
private val neventNotePattern = Regex(
    "(nostr:)?@?(nevent1|note1)([qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)
// NIP-08-style: @ followed by 64-char hex, or bare 64-char hex (word boundary so we don't match longer strings)
private val hexPubkeyPattern = Regex("(?<![0-9a-fA-F])@?([0-9a-fA-F]{64})(?![0-9a-fA-F])")
// NIP-19 naddr (addressable events, e.g. communities)
private val naddrPattern = Regex(
    "(nostr:)?@?(naddr1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]+)",
    RegexOption.IGNORE_CASE
)

private data class Segment(val start: Int, val end: Int, val type: Int, val data: Any?)
private const val SEG_URL = 0
private const val SEG_NPUB = 1
private const val SEG_NEVENT = 2
private const val SEG_EMBEDDED_MEDIA = 3
private const val SEG_NADDR = 4

/**
 * Builds AnnotatedString for note content: clickable URLs (excluding embedded media),
 * embedded media URLs hidden, @displayName for npub (NIP-19), and empty replacement for nevent/note (quoted block shown separately).
 */
fun buildNoteContentAnnotatedString(
    content: String,
    mediaUrls: Set<String>,
    linkStyle: SpanStyle,
    profileCache: ProfileMetadataCache,
    range: IntRange? = null
): AnnotatedString {
    val segments = mutableListOf<Segment>()
    val contentUrls = UrlDetector.findUrls(content)
    val embeddedMedia = mediaUrls
    val rStart = range?.first ?: 0
    val rEnd = range?.last?.plus(1) ?: content.length
    val slice = content.substring(rStart, rEnd.coerceAtMost(content.length))

    // Embedded media URL segments (hide the URL text; media is shown as image/video below)
    for (url in contentUrls) {
        if (url !in embeddedMedia) continue
        var idx = 0
        while (true) {
            val start = content.indexOf(url, idx)
            if (start < 0) break
            segments.add(Segment(start, start + url.length, SEG_EMBEDDED_MEDIA, null))
            idx = start + 1
        }
    }

    // URL segments (only link URLs that are not embedded images)
    for (url in contentUrls) {
        if (url in embeddedMedia) continue
        var idx = 0
        while (true) {
            val start = content.indexOf(url, idx)
            if (start < 0) break
            segments.add(Segment(start, start + url.length, SEG_URL, url))
            idx = start + 1
        }
    }

    // NIP-19 npub
    npubPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val hex = (parsed.entity as? NPub)?.hex ?: return@forEach
            if (hex.length == 64) {
                segments.add(Segment(match.range.first, match.range.last + 1, SEG_NPUB, hex))
            }
        } catch (_: Exception) { }
    }

    // Hex pubkey in content (e.g. NIP-08 p-tag style or pasted pubkey) – resolve via ProfileMetadataCache like npub
    hexPubkeyPattern.findAll(content).forEach { match ->
        val hex = match.groupValues.getOrNull(1)?.takeIf { it.length == 64 } ?: return@forEach
        segments.add(Segment(match.range.first, match.range.last + 1, SEG_NPUB, hex.lowercase()))
    }

    // NIP-19 naddr (addressable events, e.g. communities) – display as readable label, optional click to navigate
    naddrPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val naddr = parsed.entity as? NAddress ?: return@forEach
            // kind 34550 = NIP-72 community; use generic label for others
            val label = if (naddr.kind == 34550) "Community" else "Addressable event"
            val nostrUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
            segments.add(Segment(match.range.first, match.range.last + 1, SEG_NADDR, Triple(nostrUri, label, naddr.aTag())))
        } catch (_: Exception) { }
    }

    // NIP-19 nevent/note (replace with empty; quoted block shown below)
    neventNotePattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            when (parsed.entity) {
                is NEvent, is NNote -> segments.add(Segment(match.range.first, match.range.last + 1, SEG_NEVENT, null))
                else -> { }
            }
        } catch (_: Exception) { }
    }

    val (text, segs) = if (range != null) {
        val clamped = segments
            .filter { it.start < rEnd && it.end > rStart }
            .map { Segment(
                (it.start - rStart).coerceAtLeast(0),
                (it.end - rStart).coerceAtMost(slice.length),
                it.type,
                it.data
            ) }
            .sortedBy { it.start }
        slice to clamped
    } else {
        segments.sortBy { it.start }
        content to segments
    }

    var pos = 0
    return buildAnnotatedString {
        for (seg in segs) {
            if (seg.start < pos) continue
            append(text.substring(pos, seg.start))
            when (seg.type) {
                SEG_URL -> {
                    pushStringAnnotation("URL", seg.data as String)
                    withStyle(linkStyle) { append(seg.data as String) }
                    pop()
                }
                SEG_NPUB -> {
                    val hex = seg.data as String
                    val author = profileCache.resolveAuthor(hex)
                    val label = if (author.displayName.endsWith("...") || author.displayName == author.username) {
                        "@${author.id.take(8)}…"
                    } else {
                        "@${author.displayName}"
                    }
                    pushStringAnnotation("PROFILE", hex)
                    withStyle(linkStyle) { append(label) }
                    pop()
                }
                SEG_NEVENT -> {
                    // Replace with nothing; quoted block is rendered below
                }
                SEG_NADDR -> {
                    val (nostrUri, label, _) = seg.data as Triple<*, *, *>
                    pushStringAnnotation("NADDR", nostrUri as String)
                    withStyle(linkStyle) { append(label as String) }
                    pop()
                }
                SEG_EMBEDDED_MEDIA -> {
                    // Hide URL; embedded media is shown as image/video below content
                }
            }
            pos = seg.end
        }
        if (pos < text.length) append(text.substring(pos))
    }
}

/** Item when rendering content with HTTP metadata directly beneath each URL. */
sealed class NoteContentBlock {
    data class Content(val annotated: AnnotatedString) : NoteContentBlock()
    data class Preview(val previewInfo: UrlPreviewInfo) : NoteContentBlock()
}

/**
 * Builds content + preview blocks in order so each URL's HTTP metadata (preview) appears directly beneath that URL.
 */
fun buildNoteContentWithInlinePreviews(
    content: String,
    mediaUrls: Set<String>,
    urlPreviews: List<UrlPreviewInfo>,
    linkStyle: SpanStyle,
    profileCache: ProfileMetadataCache
): List<NoteContentBlock> {
    val urlPositions = UrlDetector.findUrlsWithPositions(content)
    val previewByUrl = urlPreviews.associateBy { it.url }
    val ordered = urlPositions
        .mapNotNull { (range, url) -> previewByUrl[url]?.let { Triple(range.first, range.last + 1, it) } }
        .sortedBy { it.first }
    if (ordered.isEmpty()) {
        val full = buildNoteContentAnnotatedString(content, mediaUrls, linkStyle, profileCache, null)
        return if (full.isNotEmpty()) listOf(NoteContentBlock.Content(full)) else emptyList()
    }
    val blocks = mutableListOf<NoteContentBlock>()
    var lastEnd = 0
    for ((urlStart, urlEnd, preview) in ordered) {
        if (lastEnd < urlEnd) {
            val chunk = buildNoteContentAnnotatedString(
                content, mediaUrls, linkStyle, profileCache,
                IntRange(lastEnd, urlEnd - 1)
            )
            if (chunk.isNotEmpty()) blocks.add(NoteContentBlock.Content(chunk))
        }
        blocks.add(NoteContentBlock.Preview(preview))
        lastEnd = urlEnd
    }
    if (lastEnd < content.length) {
        val tail = buildNoteContentAnnotatedString(
            content, mediaUrls, linkStyle, profileCache,
            IntRange(lastEnd, content.length - 1)
        )
        if (tail.isNotEmpty()) blocks.add(NoteContentBlock.Content(tail))
    }
    return blocks
}

/**
 * Extracts pubkey hex values from note content (npub decoded + 64-char hex) for kind-0 requests.
 * Use in NotesRepository (and reply repos) to request profiles for tagged/mentioned users.
 */
fun extractPubkeysFromContent(content: String): List<String> {
    val seen = mutableSetOf<String>()
    val result = mutableListOf<String>()
    npubPattern.findAll(content).forEach { match ->
        val fullUri = if (match.value.startsWith("nostr:", ignoreCase = true)) match.value else "nostr:${match.value}"
        try {
            val parsed = Nip19Parser.uriToRoute(fullUri) ?: return@forEach
            val hex = (parsed.entity as? NPub)?.hex?.lowercase() ?: return@forEach
            if (hex.length == 64 && seen.add(hex)) result.add(hex)
        } catch (_: Exception) { }
    }
    hexPubkeyPattern.findAll(content).forEach { match ->
        val hex = match.groupValues.getOrNull(1)?.takeIf { it.length == 64 }?.lowercase() ?: return@forEach
        if (seen.add(hex)) result.add(hex)
    }
    return result
}
