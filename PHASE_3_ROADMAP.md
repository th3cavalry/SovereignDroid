# Phase 3 — Persistence, Multimodal & Distribution

## Status: ✅ Complete

All features implemented and ready for release.

---

## Features Implemented

### 1. Room Database — Persistent Chat History ✅
- **Entities:** `SessionEntity`, `MessageEntity` with foreign-key cascade delete
- **DAO:** Full CRUD + LiveData observers (`ChatDao`)
- **Database:** Singleton `AppDatabase` ("sovereigndroid.db")
- **Repository:** `ChatRepository` bridges Room ↔ domain models, JSON extras via Gson
- **Migration:** One-time `migrateFromPrefsIfNeeded()` moves legacy SharedPreferences sessions to Room
- **ViewModel:** `ChatViewModel` now persists via Room instead of SharedPreferences

### 2. Multi-Turn MCP Improvements ✅
- **Parallel tool execution:** Tool calls dispatched via `coroutineScope { async/awaitAll }` for concurrent execution
- **Connection caching:** `ToolExecutor` maintains a `mcpClients` map, reusing connections across turns with error-based invalidation

### 3. Multimodal / Image Input ✅
- **API format:** `MessageDto.content` supports both `String` and `List<ContentPartDto>` (OpenAI Vision format)
- **Image picker:** `ActivityResultContracts.GetContent` for gallery selection
- **Chat display:** Thumbnail preview in input bar, inline image in user message bubbles (`ivMessageImage`)
- **Encoding:** Base64 data-URI encoding in `LLMService.encodeImageToBase64()`
- **Domain model:** `ChatMessage.imageUri` field, persisted in Room JSON extras

### 4. Document Context (RAG) ✅
- **DocumentLoader:** Reads text content from `content://` URIs, truncates at configurable limit (default 8000 chars)
- **ViewModel integration:** `setDocumentContext()` injects document text into system prompt
- **UI:** "Load Document" menu item → document picker (text/json/xml MIME types)
- **Feedback:** Snackbar with char count and "Clear" action

### 5. Home Screen Widget ✅
- **Layout:** `widget_quick_prompt.xml` — horizontal bar with hint text and send icon
- **Metadata:** `widget_info.xml` — 250×40dp min, resizable horizontally and vertically
- **Provider:** `QuickPromptWidget` — launches `MainActivity` with `EXTRA_FOCUS_INPUT=true`
- **Manifest:** Registered as `<receiver>` with `APPWIDGET_UPDATE` intent filter

### 6. Espresso UI Tests ✅
- **MainActivityTest:** 7 tests covering input visibility, send behavior, toolbar, attach button, overflow menu
- **WidgetLaunchTest:** Verifies widget intent focuses input field
- **Dependencies:** `espresso-core:3.6.1`, `ext-junit:1.2.1` (already in build.gradle.kts)

### 7. Play Store / F-Droid Prep ✅
- **Privacy policy:** `PRIVACY_POLICY.md` — covers data collection, permissions, third-party data
- **Fastlane metadata:**
  - `title.txt` — App title
  - `short_description.txt` — 80-char tagline
  - `full_description.txt` — Full feature list
  - `changelogs/8.txt` — Phase 3 changelog

---

## Files Added
| File | Purpose |
|------|---------|
| `app/src/main/java/.../db/Entities.kt` | Room entities (SessionEntity, MessageEntity) |
| `app/src/main/java/.../db/ChatDao.kt` | Room DAO |
| `app/src/main/java/.../db/AppDatabase.kt` | Room database singleton |
| `app/src/main/java/.../db/ChatRepository.kt` | Entity ↔ domain mapping |
| `app/src/main/java/.../service/DocumentLoader.kt` | RAG document text extraction |
| `app/src/main/java/.../QuickPromptWidget.kt` | Home screen widget provider |
| `app/src/main/res/layout/widget_quick_prompt.xml` | Widget layout |
| `app/src/main/res/xml/widget_info.xml` | Widget metadata |
| `app/src/androidTest/.../MainActivityTest.kt` | Espresso UI tests |
| `app/src/androidTest/.../WidgetLaunchTest.kt` | Widget launch test |
| `PRIVACY_POLICY.md` | Privacy policy |
| `fastlane/metadata/android/en-US/` | Store listing metadata |

## Files Modified
| File | Changes |
|------|---------|
| `build.gradle.kts` | Added KSP plugin |
| `app/build.gradle.kts` | Added KSP plugin, Room dependencies |
| `AndroidManifest.xml` | Registered QuickPromptWidget receiver |
| `MainActivity.kt` | Image picker, document picker, Room-backed sessions, widget intent |
| `ChatViewModel.kt` | Room repository, document context, image URI support |
| `LLMService.kt` | Parallel tool exec, base64 image encoding, vision content parts |
| `ToolExecutor.kt` | MCP client connection caching |
| `ChatDto.kt` | ContentPartDto, ImageUrlDto, polymorphic content field |
| `ChatMessage.kt` | Added imageUri field |
| `activity_main.xml` | Image preview container, attach button |
| `item_message_user.xml` | Inline image display |
| `menu_main.xml` | "Load Document" menu item |
| `strings.xml` | Widget and multimodal strings |
