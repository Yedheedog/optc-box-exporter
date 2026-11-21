# Screenshot portrait extraction

## Purpose

Portraits must remain available even when the Global CDN refuses a request or an authenticated session is unavailable. The original imported screenshot is the source of truth; extracted portraits are local derivatives.

## Extraction contract

1. Preserve the original screenshot byte-for-byte in its box profile.
2. Locate individual card bounds in a screenshot and identify the portrait sub-rectangle within each card.
3. Crop only the portrait region (not level text, buttons, badges, or adjacent cards), normalize orientation, and save a PNG under that profile's private portrait directory.
4. Associate the crop with a proposed character ID only after recognition or user confirmation. Never infer identity from the crop filename.
5. Keep confidence and source screenshot/crop coordinates so a user can inspect or replace a poor crop.
6. A crop is not a new character record. Overlap resolution happens at the physical-card level before quantities are counted.
7. Two distinct physical cards with the same character ID remain two copies. Different character IDs remain separate even when names match.
8. If automatic card/portrait bounds are uncertain, queue the crop for manual review; do not silently discard it.

## Portrait source priority

- Prefer an authenticated CDN download when it succeeds and passes image validation.
- Otherwise use the crop extracted from the user's imported screenshot.
- Allow a user-reviewed replacement crop when the automatic crop is poor.
- Cache all accepted portraits in the selected box profile so they work offline.

## Current implementation status

The Android app now provides a manual crop-review dialog: choose an imported screenshot, drag a rectangle over the portrait, and save the crop as a PNG in that profile's private `extracted_portraits/` directory. The original screenshot is preserved, crop decoding and writing run off the UI thread, and cached CDN portraits can be reused offline without a live session.

Automatic screenshot-to-card detection, automatic portrait-region detection, character-ID association, confidence scoring, overlap matching, and collection-thumbnail integration are not yet implemented. The manual crop UI saves an image only; it does not create or count a character record.
