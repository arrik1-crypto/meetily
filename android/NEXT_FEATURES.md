# Next Feature Round — Plan

Status: **planned** (follows v1.2.0's audio recording + synced playback tier).
Three candidates, in recommended build order.

## 1. Ask across all meetings

**Goal**: "When did we last discuss the vendor contract?" answered from the
whole library, not one meeting — surfacing both an answer and the source
meetings.

**Design** (no vector database; keep the on-device, zero-dependency ethos):
- **Retrieval**: reuse `MainActivity.applyFilter`-style scanning, upgraded to
  a small scorer in `search/LibrarySearch.kt`: tokenize the question (drop
  stopwords), score each meeting by term hits weighted by field
  (title > summary > action items > notes > transcript), recency-boosted.
  Meetings are already JSON on disk and load in milliseconds; a library of
  hundreds of meetings scans fine on a background thread.
- **Context packing**: take the top 3–5 meetings, extract the matching
  windows (segment neighborhoods around term hits, plus the summary), and
  build a compact context block per meeting with title + date labels.
- **Answering**: reuse `LlmClient.ask` with a multi-meeting prompt that
  requires citing which meeting each claim came from. No LLM configured →
  fall back to a "matching moments" list (meeting, date, matched excerpt) —
  still genuinely useful.
- **UI**: an "Ask your library" entry point on the home screen (search bar
  action or FAB long-press) opening a lightweight Q&A sheet; answers link to
  the source meetings.
- **Effort**: medium. Pure Kotlin + one new prompt; no new native deps.

## 2. Background import service

**Goal**: file import shouldn't require keeping the screen on; today
`ImportActivity` dies with the activity (partial transcript survives).

**Design**:
- `ImportService`: a started foreground service (`dataSync` type — needs
  `FOREGROUND_SERVICE_DATA_SYNC` permission + manifest declaration) that owns
  the `AudioFileImporter` run: progress notification with percent + cancel
  action, same incremental saves.
- `ImportActivity` becomes a thin bound observer, mirroring exactly the
  `RecordingService`/`RecordingActivity` split that already works — reuse
  that observer pattern (bind, observer interface, notification tap
  reopens).
- Queue: accept multiple share intents; process sequentially (one Whisper
  context at a time). Refuse (or queue behind) live recording, as now.
- Completion notification opens the meeting.
- **Effort**: medium-low — mostly moving existing code across the
  service boundary we've already built once.

## 3. Quick-record entry points

**Goal**: one tap from anywhere to start recording.

**Design**:
- **Quick Settings tile** (`TileService`): "Record meeting" tile; tap starts
  `RecordingService` (mic permission already granted after first run) and
  opens `RecordingActivity`. Android 14+: launching activities from tiles
  needs `startActivityAndCollapse` with a PendingIntent.
- **Home-screen widget** (`AppWidgetProvider`, simple RemoteViews): one
  record button + optional "last meeting" shortcut. Keep static (no
  ListView) for reliability.
- **App shortcuts** (`shortcuts.xml`, cheapest of the three): long-press the
  launcher icon → "Record", "Import audio", "Search".
- Consent gate: all entry points route through the existing consent check
  before capture starts.
- **Effort**: low for shortcuts, low-medium for tile, medium for widget
  (RemoteViews theming is fiddly across launchers).

## Suggested order

Shortcuts + QS tile first (fast win), then background import, then
ask-across-meetings (the deepest feature, best shipped with time to tune
the retrieval scorer).
