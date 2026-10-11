# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

TravelLink AI is an Android travel itinerary planning app for the Taiwan/Taitung region. It uses Gemini 2.5 Flash (via Vertex AI) to generate structured itinerary JSON, Imagen 3.0 for travel guide illustrations, Firestore for real-time multi-user collaboration, and Room SQLite for offline-first local storage.

## Build & Run Commands

```bash
# Build
./gradlew assembleDebug
./gradlew assembleRelease

# Install on connected device/emulator
./gradlew installDebug

# Tests
./gradlew test                    # Unit tests
./gradlew connectedAndroidTest    # Instrumented tests (requires device)
```

**Firebase Cloud Functions (Python):**
```bash
cd functions
pip install -r requirements.txt
firebase emulators:start           # Local emulation
firebase deploy --only functions   # Deploy to production
```

**Data seeding scripts:**
```bash
python scripts/seed_poi_knowledge.py --dry-run
python scripts/auto_discover.py --dry-run
```

## Architecture

### Android App (`app/`)

**Dependency injection:** Hilt (`@AndroidEntryPoint` on `MainActivity`, `@HiltViewModel` on all ViewModels). `AppModule.kt` provides `AppDatabase`, `FirebaseAuth`, `FirebaseFirestore`, `FirebaseStorage`, and `CollabIdentityManager` as singletons.

**State management:** Jetpack ViewModel + Kotlin StateFlow. `ItineraryViewModel.kt` (~4100 lines) is the central state owner — it orchestrates AI calls, Firestore sync, collaboration presence, map state, and multi-screen navigation. `ItineraryStateHolder.kt` in `data/repository/` is a shared singleton holding itinerary state between ViewModels.

**Screen flow:** `MainActivity.kt` owns `currentScreen` state. Entry is gated by `AuthViewModel.authState`:
- `AuthState.Unauthenticated` → `LoginScreen`
- `AuthState.Authenticated` → main app: `home → planning wizard → AI result (map/image view) → feedback`
- Drawer routes to: history, preferences, about, profile, group list (`GroupListScreen`/`GroupScreen`)

**Auth layer:** `AuthViewModel` handles Firebase Auth email/password login and registration, persisting a `UserProfile` to Firestore `users/{uid}`. `LoginScreen` and `ProfileScreen` are in `ui/auth/`.

**Data layer has two tiers:**
- **Local:** Room SQLite (`AppDatabase.kt`, migrations v3→v11) for offline-first history. Entities are `LocalItinerary` and `LocalTripMemory`. `UserPreferencesManager` (DataStore) stores travel style preferences.
- **Remote:** Firestore for real-time sync and collaborative editing. `CollabModels.kt` defines presence and editing lock data classes. `CollabIdentityManager` provides a stable anonymous identity across sessions.

**Key UI files:**
- `PlanningBottomSheet.kt` (46KB) — wizard input for date, companions, travel style, pace
- `ItineraryPreviewScreen.kt` (53KB) — displays AI-generated itinerary with map or illustration view
- `ItineraryMapScreen.kt` — Google Maps with polyline route rendering (`PolyUtil.kt`)
- `GroupListScreen.kt` / `GroupScreen.kt` — collaborative group management UI (new, under `ui/collab/`)

### Cloud Functions (`functions/main.py`)

Three HTTP-callable functions:
- `generate_itinerary()` — calls Gemini 2.5 Flash with a structured prompt; returns JSON `{title, stops[], aiReply}`
- `generate_image()` — calls Imagen 3.0 to produce hand-drawn style 16:9 travel illustrations
- `_build_itinerary_prompt()` — internal helper that constructs the Gemini prompt

Runtime: Python 3.14, timeout 120–300s, max 10 instances.

### Collaboration Flow

1. User creates an itinerary → saved to Firestore, gets a `firestoreDocId`
2. Share via deep link: `travellink://join/{docId}`
3. `MainActivity` handles the deep link intent and routes to the collaborative session
4. Firestore listeners push real-time updates; editing locks (`CollabModels.kt`) prevent conflicts

## Key Configuration

- **Firebase project:** `project-720a680b-3ad1-40d1-b07` (`.firebaserc`, `google-services.json`)。舊文件曾寫 `travel-link-ai-2`，該專案已失效（CONSUMER_INVALID），勿再使用
- **Vertex AI project:** `project-720a680b-3ad1-40d1-b07`, region `us-central1`
- **Dependency versions:** managed centrally in `gradle/libs.versions.toml` (Compose BOM 2024.10.00, Firebase BOM 33.10.0, Room 2.7.0)
- **Min SDK:** 26, **Target SDK:** 35

## Important Notes

- API keys are **not** hardcoded: they are read from `local.properties` (gitignored) in `app/build.gradle.kts` and injected via `manifestPlaceholders` / `buildConfigField`. Do not move them into source or commit `local.properties`.
- `scripts/serviceAccount.json` is gitignored (Firebase admin key); it must exist locally to run seeding scripts.
- Room database is currently at version 11; any schema change requires a new migration in `AppDatabase.kt`.
- Trip photos live in the shared album `micro_trips/{tripId}/photos/{photoId}` (same data as the web). Rules lock the doc to an 18-field whitelist — extra fields reject the whole write — so only write via `TripPhoto.toFirestoreMap()` / `TripPhotoRepository`. Storage path is `trip-photos/{uid}/{tripId}/{photoId}.jpg` with `contentType image/jpeg`; stop membership comes only from the `stopId` field. Owner/editor may delete or re-sort others' photos; the `cleanup_deleted_trip_photo` Cloud Function removes the leftover Storage file and the legacy `memories` URL.
- Trip notes live at `micro_trips/{tripId}/memories/{uid}` (one doc per member, owner-writable). Its `spots.*.photos` arrays are legacy: still read (merged with `photos`, deduped by storagePath/url) and never deleted, because older app versions only read there. `TripPhotoRepository.migrateLegacy()` copies the user's own legacy photos into `photos` on open. The older `users/{uid}/memories/{tripId}` path is also legacy; `MemoryViewModel.migrateLegacyMemory()` moves it.
- Photo/recap `ownerName` comes from `MyNameProvider` (the in-app nickname `users/{uid}.name`); identity is always `ownerUid`, and name collisions are disambiguated at display time by `ownerLabels()`.
- Saving images to the gallery must go through `util/MediaStoreSaver.kt` — `MediaStore.Images.Media.RELATIVE_PATH` is API 29+ only and throws on API 26–28.
- The ViewModel is intentionally monolithic — splitting it would require careful StateFlow refactoring to avoid breaking Firestore listeners.
