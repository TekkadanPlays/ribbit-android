# Ribbit Feed Experience Plan

This document outlines the current state, Amethyst-inspired patterns, and a phased plan to deliver an excellent feed experience—including markdown, video, GIFs, and other content types.

---

## 1. Monitoring Incoming Events (Done)

**Use this to see what relays are actually sending.**

- In **debug builds**, every kind-1 event is logged with a one-line summary.
- **How to monitor:** Run the app, then in a terminal:
  ```bash
  adb logcat -s RibbitEvent
  ```
  Or in Android Studio Logcat, set the filter to `RibbitEvent`.

**Logged per event:**
- `id` (first 8 chars), `relay` (last 25 chars of URL)
- `len` = content length
- Tag counts: `e`, `p`, `t`, `imeta`, `emoji`
- URL stats: total URLs, image count, video count, whether any URL is a GIF
- `md` = whether content looks like markdown (`**`, `##`, `` ``` ``, `[text](url)`)

This helps you see how often notes use markdown, video, GIFs, NIP-30 emoji, and NIP-94-style `imeta` in the wild.

---

## 2. Current Ribbit State

| Area | What we have |
|------|----------------|
| **Content** | Plain text; URLs detected and linkified; npub/nevent/note (NIP-19) parsed; embedded *images* (by URL extension) shown below content; URL previews (HTTP metadata) under link URLs. |
| **Media** | `UrlDetector.isImageUrl()` → .jpg, .jpeg, .png, .gif, .webp, .bmp, .svg. Images rendered (e.g. Coil); **GIF** supported via `coil-gif`. **Video** URLs detected (`UrlDetector.isVideoUrl()`) but not yet rendered as video players. |
| **Markdown** | Not rendered; content shown as plain text (bold/headers/links appear as raw `**`, `##`, `[text](url)`). |
| **Tags** | We use `e`, `p`, `t` (hashtags). We do **not** yet parse `imeta` (NIP-94), `emoji` (NIP-30), or `content-warning` (NIP-36). |
| **Note model** | `mediaUrls` = image URLs only; no separate video list; no blurhash/dimensions/mime from NIP-94. |

---

## 3. Amethyst (Reference) – What to Aim For

Amethyst is in `external/amethyst` and uses Quartz (same as us). Patterns worth reusing or adapting:

- **RichTextParser** (commons): Parses content into segments (text, links, images, video, base64, NIP-19, hashtags, **custom emoji**). Uses **NIP-94-style metadata** (imeta by URL: mime, blurhash, dimensions, alt). Treats **video** and **image** URLs distinctly (video extensions: mp4, webm, mov, m3u8, etc.).
- **Media types**: `MediaUrlImage`, `MediaUrlVideo` with optional description, hash, blurhash, dimensions, content warning.
- **Markdown**: Amethyst uses `com.github.vitorpamplona.compose-richtext` (richtext-commonmark, richtext-ui, richtext-ui-material3) for Compose markdown rendering.
- **Custom emoji (NIP-30)**: `emoji` tags `["emoji", shortcode, image-url]`; content uses `:shortcode:`; EmojiCoder + segment type `EmojiSegment`.
- **Content warning (NIP-36)**: Hide sensitive content until user taps “Show”.
- **Gallery**: Multiple images/videos in one note grouped and laid out (e.g. GalleryParser, ImageGalleryParagraph).
- **Videos**: Amethyst removed autoplay in 0.5 (battery); optional “automatically play videos” setting; video codec (H.265) option.

---

## 4. NIPs to Support for a Rich Feed

| NIP | Purpose | Priority |
|-----|---------|----------|
| **NIP-30** | Custom emoji (`:shortcode:` + `emoji` tag with URL) | High (common in feeds) |
| **NIP-36** | Content warning tag; hide content until user confirms | High (safety/UX) |
| **NIP-94** | File metadata (mime, blurhash, dim, alt) for media URLs; optional `imeta` on events | Medium (better thumbnails and video vs image) |
| **NIP-54** | Inline metadata (e.g. in URL fragments) | Medium (can complement NIP-94) |

Markdown is not a NIP; it’s a convention. Many clients use CommonMark-style formatting.

---

## 5. Phased Plan for an Amazing Feed

### Phase 1 – Correct media and simple UX (foundation)
- **Video in feed**: Classify URLs as image vs video (reuse `UrlDetector.isVideoUrl()`); for video URLs, render a small player or thumbnail + play button (no autoplay; tap to play). Store `videoUrls` (or a unified `mediaUrls` with type) on `Note` if needed.
- **GIFs**: We already have coil-gif; ensure all image URLs that end in `.gif` (or that NIP-94 marks as image/gif) are in `mediaUrls` and rendered as images (GIFs animate in Coil).
- **Single media block**: One note can have both images and videos; show them in one block below content (e.g. images first, then videos, or in URL order) without pushing other UI around.

### Phase 2 – Markdown
- Add a markdown renderer for note content (e.g. Compose RichText / commonmark like Amethyst, or a lightweight parser).
- Render **bold**, *italic*, headers, `[text](url)` links, and fenced code blocks. Keep npub/nevent handling so links still open profiles/threads.
- Optional: start with a subset (bold + links + code) and expand.

### Phase 3 – NIP-30 (custom emoji) and NIP-36 (content warning)
- **NIP-30**: Parse `emoji` tags from the event; build a shortcode → image URL map; in content, replace `:shortcode:` with the emoji image (inline, small).
- **NIP-36**: If event has `content-warning` (and optionally reason), show a “Sensitive content – Show” curtain; on tap, reveal content (and media).

### Phase 4 – Richer media (NIP-94 / imeta)
- Parse `imeta` (or NIP-94) tags and associate metadata (mime, blurhash, dimensions, alt) with URLs.
- Use blurhash for image placeholders; use mime/dimensions to treat URL as image vs video when extension is missing or ambiguous.
- Optional: show alt text for images (accessibility).

### Phase 5 – Polish and performance
- **Gallery layout**: When a note has multiple images/videos, show a compact grid or horizontal strip (like Amethyst’s ImageGalleryParagraph) instead of a long vertical list.
- **Video policy**: No autoplay by default; optional “Play videos automatically” in settings; consider data saver (no autoplay on cellular).
- **Performance**: Lazy-load media; limit simultaneous decodes (GIFs/videos); reuse learnings from Amethyst (e.g. no background autoplay).

---

## 6. How to Use Event Monitoring

1. Build and run the app in **debug** (e.g. Run in Android Studio).
2. Open the feed and let notes load from your relays.
3. In a terminal: `adb logcat -s RibbitEvent` (or filter Logcat by `RibbitEvent`).
4. Watch lines like:
   - `md=true` → note uses markdown-like syntax.
   - `img=1 vid=1 gif=true` → note has image, video, and GIF URLs.
   - `imeta=1 emoji=2` → note uses NIP-94-style metadata and NIP-30 emoji.

Use this to decide which phases to prioritize (e.g. if most notes have `md=true`, markdown is high value; if `imeta` is rare, NIP-94 can wait).

You can also use `adb logcat -s NotesRepository`: event stats are sampled every 20th note on the same tag, so one terminal shows both subscription lifecycle and a sample of `id=`, `len=`, `img=`, `vid=`, `gif=`, `md=`, etc.

### Observed stats (fill in after collecting 100–200 samples)

After running the app and capturing a minute of feed activity (or 100–200 event sample lines), note approximate shares here to revise phase priority:

- **md=true**: ____ %
- **img>0**: ____ %
- **vid>0**: ____ %
- **gif=true**: ____ %
- **imeta>0**: ____ %
- **emoji>0**: ____ %

**Revised priority (if data suggests):** _e.g. "~40% md=true → Phase 2 markdown high priority"_

---

## 7. Summary

- **Event monitoring** is in place (debug only, tag `RibbitEvent`) so you can see what’s coming from relays.
- **Plan**: Fix video/GIF rendering and layout (Phase 1), add markdown (Phase 2), then NIP-30 + NIP-36 (Phase 3), then NIP-94 and gallery/polish (Phases 4–5).
- Amethyst’s `RichTextParser`, media types, and compose-richtext markdown in `external/amethyst` are good references; we can adopt patterns incrementally without copying the whole stack.

Running the app and watching `RibbitEvent` logs will show exactly what your relays send (markdown, videos, GIFs, emoji, imeta) and keep the plan aligned with real usage.
