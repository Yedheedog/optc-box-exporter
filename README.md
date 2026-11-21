# OPTC Box Manager

An Android-first, standalone box manager for One Piece Treasure Cruise. This project is independent from OPTC Farm Lite / Function Omega.

## Android app

Open the repository root in Android Studio and sync the Gradle project. The Android module is in `app/`.

Current scaffold (version 0.1.0):
- Create separately named Global or Japan box profiles.
- Store each profile's screenshots in its own app-private directory.
- Import multiple screenshots from the Android gallery using the system document picker.
- Export a versioned JSON document (JSON only; no HTML export).
- Optionally import the Farm Lite device profile as a separate authentication source. The selected file is encrypted with an Android Keystore AES-GCM key and stored outside box-profile data. The network login adapter is not connected yet.

The app currently exports the profile manifest and screenshot filenames. Character recognition has not yet been connected, so the characters array is intentionally empty until recognition is implemented and validated. No APK has been built or released yet.

## JSON format

The original OPTC Box Exporter master branch did not define a persistent JSON box-export format. This project defines a new documented format in [docs/JSON_EXPORT_SCHEMA.md](docs/JSON_EXPORT_SCHEMA.md). Each character ID will have one record with a quantity; separate IDs remain separate even when display names match.

## Recognition and portrait work

The legacy Python detector and matcher remain in the repository as research/reference code. They are not yet integrated into the Android app. Recognition must be validated against current game screenshots before being used for collection counts.

Farm Lite's Global helper uses this portrait URL pattern:

`https://cdn.gb.onepiece-tc.jp/en/images/characters/character_####_t.png`

The helper fetches the CDN URL directly. An Android download/cache utility now uses that Global URL pattern, requires the active authenticated game session's X-SESSION header (and carries the game User-Agent/Authorization headers), validates image responses, limits download size, and stores portraits in app-private storage. The authentication adapter is not connected yet, so the downloader deliberately cannot be called successfully without session headers. CDN behavior still needs an end-to-end authenticated test, and the utility is not yet wired into the collection screen.

A profile-scoped screenshot portrait crop utility has also been added. It accepts a reviewed normalized crop rectangle, verifies that the source screenshot belongs to the selected profile, and saves a PNG under that profile's private `extracted_portraits/` directory without modifying the original screenshot. This is the offline fallback when CDN access is denied. Automatic card-grid/portrait detection and the interactive crop-review UI are still pending; the crop utility does not claim to recognize character IDs by itself. The Japan-server CDN URL has not yet been verified; the app deliberately refuses Japan portrait downloads rather than guessing a hostname.

The legacy matcher expects:
- `data/units.json` — character metadata.
- `data/Portraits/*.png` — reference portraits named by character ID.
- `ai/config.yml` and `ai/checkpoint.pt` — optional legacy SSD detector assets.

The Farm Lite static `MstCharacter_` table may be imported as a copied reference dataset when an authorized static export is available. Do not use Farm Lite inventory or account data as box contents.

## Accuracy requirements

- Exact duplicate screenshots can be skipped by file hash.
- Overlapping screenshots require spatial/visual matching of the same physical card location. Never deduplicate solely by character ID: real duplicate copies must remain countable.
- Ambiguous overlap matches must be shown for review rather than silently discarded.
- Duplicate IDs will be grouped with a quantity badge (for example, ×2) in the collection UI.

## Security and project separation

- Box profiles are local app data and are independent of game accounts.
- The optional Farm Lite auth source is stored separately and encrypted with Android Keystore.
- Never commit device profiles, session tokens, account credentials, or live inventory data.
- Farm Lite is a read-only reference for this project; no Farm Lite farming or account behavior is modified.
