# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A **static HTML prototype playground** for an AI travel‑planning app ("TravelLinkAI", renamed from "WanderAI" on 2026-07-26). There is **no build step, no framework, no bundler**. Prototypes run directly from the filesystem (`file://`) or any static server. The most current / main flow is [ai-travel-planner-v8.html](app/ai-travel-planner-v8.html); the explore + login + onboarding flow is [ai-travel-explore-final.html](app/ai-travel-explore-final.html); [intro.html](app/intro.html) is the marketing / onboarding landing page for new users.

**Read [AGENTS.md](AGENTS.md) before any task** — it holds the authoritative working rules and a required post-change user-flow testing protocol (see below).

## Folder layout

```
app/        ← all web-app runtime files (HTML, CSS, JS, data, config)
archive/    ← currently empty (its last file moved to app/intro.html)
prototypes/ ← standalone design mockups, not wired to any service or served by nginx
tools/      ← one-off utility scripts (patch_gen_overlay.py, nginx-blocklist.conf)
crawler/    ← Node.js data pipeline (npm project, run from that folder)
server/     ← Node.js backend proxy for Vertex/Gemini (keeps the API key server-side; see DEPLOY.md)
```

## Commands

There is **no test/lint/build at the repo root**. Validation is manual (open the page, exercise the flow). To preview a prototype, open its `.html` via `file://` or serve the folder statically.

- **Syntax gate (enforced automatically):** a `PostToolUse` hook ([.claude/settings.json](.claude/settings.json) → [.claude/hooks/check-edited-js.js](.claude/hooks/check-edited-js.js)) runs `node --check` on every `.js` you Write/Edit. Run it yourself with `node --check <file>.js`.

The only npm project is the **crawler** ([crawler/](crawler/), run from that folder after `npm install`). It needs `$env:FIREBASE_SERVICE_ACCOUNT_PATH` pointing at `crawler/serviceAccount.json`; the Google Maps key is auto-loaded from `app/weather.env.js`. See [crawler/README.md](crawler/README.md) for full detail.

| Command | Purpose |
|---|---|
| `npm run import:taitung` (`import:taitung:dry`) | **台東觀光旅遊網 opendata (265 spots) → Firestore `scenic_points`.** Use this one. 100% have address/coords/photo; `opentimeGoogle` is the same 7-line format `business-hours.js` parses. Photos land in Firestore as `photoUrl` but are **not** exported to `app/poi-data.js` (image licensing unconfirmed) |
| `npm run import` (`import:dry`) | Same, `--source=auto`: tries the national feed first, falls back to Taitung. **The national feed (`media.taiwan.net.tw`) has been 404 since ~2026-09** — the tourism bureau moved it to the auth-gated TDX. `--source=national` now throws with an explanation instead of silently importing 0. Set `OPENDATA_SOURCE_URL` if a working national feed reappears |
| `npm run verify:places` (`-- --force`, `-- --limit N`) | Strict-name-match each scenic point against Google Places; write back precise coords / hours / rating / `place_id` (keeps OpenData name) |
| `npm run enrich:fees` (`-- --force`, `-- --loose`, `-- --tdx`) | Match real ticket prices (主來源: 台東觀光網 opendata, optional `--tdx` for nationwide TDX) → write `fee`/`feeNote`/`feeSource` back onto `scenic_points` |
| `npm run export:local` (`export:local:dry`) | `scenic_points` → [app/poi-data.js](app/poi-data.js); also exports `parking_lots` → [app/parking-data.js](app/parking-data.js) when that collection is non-empty |
| `npm run crawl:food` (`crawl:food:dry`) | Restaurants per destination (Places `searchNearby`, incl. `costPerPerson`/`costNote`) → [app/restaurant-data.js](app/restaurant-data.js) |
| `npm run crawl:parking` (`crawl:parking:dry`) | Taitung County government open-data XLSX (public/private off-street parking lots, [dataset 165292](https://data.gov.tw/dataset/165292)) → geocode addresses → Firestore `parking_lots`. **Needs a non-referrer-restricted `GOOGLE_MAPS_API_KEY`** (`app/weather.env.js`'s key is browser-only and gets 403'd server-side); pass one via `$env:GOOGLE_MAPS_API_KEY` before running. |

A Windows scheduled task **`WanderAI Food Crawl`** runs `crawl:food` biweekly via [crawler/run-food-crawl.bat](crawler/run-food-crawl.bat).

## Architecture

### Prototype file-split convention (important)
- All app files live in [app/](app/). `ai-travel-planner-v8.html` and `ai-travel-explore-final.html` load **sibling external `.css`/`.js`** (e.g. [app/ai-travel-planner-v8.js](app/ai-travel-planner-v8.js), [app/ai-travel-explore-final.js](app/ai-travel-explore-final.js)). **Put logic/style changes in those files**; the `.html` keeps only markup, CDN/`weather.env.js` script tags, and the Google Maps loader. They are plain `<script src>` (not ES modules) so `file://` works.
- [app/intro.html](app/intro.html) (the landing page) and the mockups under [prototypes/](prototypes/) keep CSS/JS **inline** — they are self-contained single files, so the split convention above does not apply to them. `intro.html` still needs to live in `app/` because it loads `weather.env.js` and links to the two main pages as siblings.
- Keep changes local to the target prototype; only backport across variants when explicitly asked.

### Runtime config & external services
- [app/weather.env.js](app/weather.env.js) (gitignored) defines `window.TRAVEL_APP_CONFIG`: `GOOGLE_MAPS_API_KEY`, `VERTEX_PROJECT_ID` (not secret — same as Firebase projectId), Firebase config, `API_PROXY_BASE`. Never hardcode keys in page markup — read them from here. **`VERTEX_API_KEY` is no longer here** — it moved to `server/.env` (see below).
- [app/ferry-config.js](app/ferry-config.js) (`window.WAI_FERRY_CONFIG`) holds stable island-harbor anchor coordinates (Places often mis-geocodes these).
- [app/attraction-fee-config.js](app/attraction-fee-config.js) (`window.WAI_ATTRACTION_FEE`) is a **hand-maintained** override layer for admission fees — unlike the other data files below, edit it directly; it takes priority over the `fee`/`feeNote` that `enrich:fees` writes into `app/poi-data.js`.
- Services, all loaded via CDN (`file://`-compatible): **Firebase** (Auth + Firestore), **Google Maps Places API (New)**, **Vertex AI / Gemini** (trip generation).
- **Vertex/Gemini calls go through a backend proxy** ([server/](server/), Node + Express). When `TRAVEL_APP_CONFIG.API_PROXY_BASE` is set (e.g. `'/api'`), the frontend calls the same-origin `/api/vertex/*` instead of `aiplatform.googleapis.com/...?key=`; the proxy injects `VERTEX_API_KEY` (from `server/.env`) so the key never reaches the browser. Both files build this via `VERTEX_HOST`/`VERTEX_API_BASE` and `getVertexConfig()` (`ready` is true in proxy mode without a client-side key). Leaving `API_PROXY_BASE` empty falls back to direct calls (needs a client-side key; dev only). Maps JS + Firebase keys **can't** be proxied (they run in the browser) — protect those with HTTP-referrer / authorized-domain restrictions. `file://` won't hit the proxy; test proxy mode via a static server + the Node proxy (or nginx). See [server/README.md](server/README.md) and [DEPLOY.md](DEPLOY.md).

### Local-first data pipeline (the core cost design — spans crawler + frontend)
Per-trip Google Places calls are expensive, so generated scenic/restaurant data is **cached into static files and used local-first**:

- Data files (auto-generated, do not hand-edit): [app/poi-data.js](app/poi-data.js) = `window.WAI_POI_DATA` (scenic spots), [app/restaurant-data.js](app/restaurant-data.js) = `window.WAI_RESTAURANT_DATA` (restaurants), [app/parking-data.js](app/parking-data.js) = `window.WAI_PARKING_DATA` (Taitung-mainland off-street parking lots, flat `taitungCounty` array — not bucketed by destination like the other two). Loaded via `<script src>` in `ai-travel-explore-final.html` (poi/restaurant) or `ai-travel-planner-v8.html` (parking). (The one exception is [app/attraction-fee-config.js](app/attraction-fee-config.js) — hand-maintained, see above.)
- **Pipeline:** OpenData → `scenic_points` (Firestore) → `verify:places` (Places enrichment) + `enrich:fees` (ticket-price enrichment, 台東觀光網 opendata + optional `--tdx`), both written back to Firestore → `export:local` → `app/poi-data.js`; `crawl:food` (Places `searchNearby`) → `app/restaurant-data.js`; and `crawl:parking` (Taitung County government XLSX + geocoding) → Firestore `parking_lots` → `export:local` → `app/parking-data.js`.
- **Frontend (`ai-travel-explore-final.js`) is local-first:** if a destination exists in `app/poi-data.js`, the generator uses the local POI list and **skips** the live Places fetch and the Firebase `poi_cache`/reviews path. `getLocalPoiList` / `getLocalFoodList` do the lookup; `fetchGoogleMapsFoodList` is local-first too.
- After the AI produces a trip, `verifyAndFilterStopsWithPlaces` validates final stops — but **short-circuits** stops whose name matches a verified local POI/cached restaurant (skips the Places call). This is what keeps runtime Places usage low.
- **Cost surfacing (`ai-travel-planner-v8.js`):** real ticket prices (`fee`/`feeNote` from `app/poi-data.js`, overridable via `app/attraction-fee-config.js`) and restaurant per-person cost (`costPerPerson`/`costNote` from `app/restaurant-data.js`, derived from Places `priceLevel`/`priceRange`) are shown on itinerary stop cards and folded into the per-person budget breakdown.
- **Refresh:** scenic coords/hours = `verify:places -- --force` → `export:local`; scenic fees = `enrich:fees -- --force` → `export:local`; restaurants = `crawl:food` (mostly automatic via the scheduled task). Refreshing only changes the cache/hints; final-trip coords/hours are still re-verified live for non-cached stops.
- Estimated/heuristic, not from Places: scenic **stay duration** (`estimateDuration` in [crawler/worker.js](crawler/worker.js) — category table + desc parsing, to avoid the costly Places reviews SKU).

### Crawler internals
[crawler/worker.js](crawler/worker.js) is a single Node script; mode is chosen by CLI flag (`--import` / `--verify-places` / `--enrich-fees` / `--export-local` / `--crawl-food` / `--crawl-parking`, plus `--dry-run`, `--force`, `--limit`). Reads Firestore via `firebase-admin`. `deriveDestKey` buckets POIs into frontend destination keys (台東 / 綠島 / 蘭嶼 …); `placeNameMatchesQuery` is the strict matcher shared in spirit with the frontend verify step (臺→台 normalization matters throughout). `--enrich-fees` additionally needs `TDX_APP_ID`/`TDX_APP_KEY` (only when passing `-- --tdx`) and looks up `app/weather.env.js` first, falling back to the repo-root copy for compatibility. `--crawl-parking` parses XLSX with a hand-rolled zero-dependency zip/XML reader (deliberately not the npm `xlsx` package — its latest npm release has an unpatched prototype-pollution/ReDoS advisory) and needs its own non-referrer-restricted Google Maps key (see command table above).

## Post-change testing (from AGENTS.md)
After a change, **run the affected feature from the start of the flow as a real user**, not just the edited line. Check state/ordering, preview/summary sync, persistence, and that user-entered values are preserved unless a feature must correct them. Itinerary-time edits have explicit rules (later stops auto-delay only when earlier-or-equal to the previous stop; manual edits otherwise preserved). Report findings using the "User Flow Test Result" template in AGENTS.md. Note in the report anything blocked locally (Firebase/Maps keys, network, `localStorage`).
