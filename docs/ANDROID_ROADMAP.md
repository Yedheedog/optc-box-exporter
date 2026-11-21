# Android implementation roadmap

## Current scaffold

- [x] Android Gradle application module and Compose launcher.
- [x] Named local Global/Japan box profiles.
- [x] Per-profile private screenshot directories.
- [x] Multi-select Android gallery import.
- [x] SHA-256 filtering for byte-identical screenshots within one profile.
- [x] Versioned JSON-only export action and documented schema.
- [x] Shared static character-reference JSON importer, validated by unique positive server character IDs.
- [x] Separate encrypted Farm Lite auth-source vault using Android Keystore AES-GCM.
- [x] Global CDN portrait downloader/cache utility based on Farm Lite's helper URL pattern.
- [x] Profile-scoped screenshot portrait crop utility that preserves originals and saves validated PNG crops offline.
- [x] Manual screenshot crop-review dialog with drag-to-select bounds and profile-private PNG output.
- [x] Retire the HTML export endpoint and HTML builder from this feature branch.
- [ ] Build and verify a debug APK on Android CI/device.

## Next implementation sequence

1. **Character reference data**
   - [x] Add an Android importer for the static JSON produced by `tools/import_farm_lite_db.py`; the catalogue is shared across box profiles.
   - Obtain an authorized static export of Farm Lite's MstCharacter_ master table or a verified current OPTC reference source.
   - Preserve the numeric server character ID as the stable key.
   - Record source version/date and license/provenance.
   - The public optc-db/optc-db.github.io repository is GPL-3.0 and its latest reported push is 2024-08-14; treat it as a historical fallback, not the current source of truth, and review GPL obligations before reusing code or data.
   - Do not import live inventory, user characters, or account-save contents.
2. **Screenshot recognition**
   - Validate card-grid detection against representative current Global and Japan screenshots.
   - Match card portraits against the static reference set.
   - Return confidence and an inspectable crop for each proposed match.
3. **Overlap review**
   - Detect screenshot-to-screenshot overlap using visual landmarks and relative card positions.
   - Treat a repeated physical card at an overlapping screen position as one unit.
   - Never merge separate physical copies merely because character IDs match.
   - Put ambiguous matches in a user review queue; never silently discard them.
4. **Collection UI**
   - Browse and search by character name and ID.
   - Group copies by character ID and show quantity badges at the portrait's bottom-right.
   - Keep different IDs separate even when names are identical.
5. **Portrait extraction and cache integration**
   - [x] Connect the screenshot crop utility to a manual visual crop-review screen; automatic card-grid and portrait-region detection remain pending.
   - Connect both extracted portraits and the Global CDN cache utility to collection thumbnails and offline display. Cached CDN portraits can now be read offline without a live session.
   - Verify Japan's actual CDN host/path before enabling Japan downloads.
   - Complete the login adapter using the encrypted Farm Lite device profile, then pass the live X-SESSION and matching game-client headers to every portrait CDN request. The portrait cache now rejects requests without X-SESSION.
6. **JSON export**
   - Export recognized IDs, quantities, metadata, profile/server identity, and schema version.
   - Include portrait assets in a bundle or omit portrait paths; never emit broken relative paths.
   - Add automated schema and count-preservation tests.

## Release gate

Do not label the app as a completed box scanner or publish a release APK until recognition accuracy, overlap deduplication, duplicate quantities, offline portraits, and JSON round-trip tests pass on real screenshots from both supported servers.
