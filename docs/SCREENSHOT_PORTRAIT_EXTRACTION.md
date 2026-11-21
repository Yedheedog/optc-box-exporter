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

The Android app provides two screenshot extraction paths. **Auto-extract card images** runs an offline OpenCV gradient/contour detector over a downsampled screenshot and saves candidate square card tiles as PNGs in that profile's private `extracted_portraits/` directory. Candidate crops are review assets only: the detector does not identify character IDs, count copies, or resolve overlapping screenshots. If it cannot find a clear grid, use the manual crop-review tool. The manual tool lets the user drag a rectangle over an individual portrait. Crops are decoded with bounded memory, with a sampled fallback for Android codecs that do not support region decoding. The original screenshot is preserved, and manually reviewed crops can be assigned to a character ID from the shared reference catalogue. Cached CDN portraits can be reused offline without a live session.

Automatic screenshot-to-card detection, automatic portrait-region detection, character-ID association, confidence scoring, overlap matching, and collection-thumbnail integration are not yet implemented. The manual crop UI saves an image only; it does not create or count a character record.
