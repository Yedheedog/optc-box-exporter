# OPTC Box Exporter

A standalone screenshot-to-HTML exporter for One Piece Treasure Cruise character boxes.

## Intended workflow

1. Take screenshots of your character box in the official game.
2. Select all screenshots in the exporter.
3. Run recognition.
4. Download one self-contained `optc-character-box.html` file. It includes the detected portrait crops and works offline.

The exporter does **not** log in to the game, call account or inventory APIs, or read OPTC Farm Lite account saves.

## Run locally

Install the dependencies in `requirements.txt`, make sure the recognition assets described below are present, and start the Flask app:

```bash
python -m pip install -r requirements.txt
python -m flask --app optcbx.app_flask run
```

## Recognition assets

The original matcher expects:

- `data/units.json` — character metadata used by the legacy matcher.
- `data/Portraits/*.png` — reference portraits named by character ID.
- `ai/config.yml` and `ai/checkpoint.pt` — the legacy SSD detector, if using its smart detection mode.

The Farm Lite source repository defines the static character master table as `MstCharacter_` and uses fields such as `serverId_`, `name_`, `attributeId_`, `subAttributeId_`, and `characterType_`. Its generated `sakura.db` is not committed in that repository. A future data-import step will convert an authorized static export of that table into this project's independent reference dataset. No Farm Lite runtime or account data is required.

## Current implementation notes

- Multiple screenshots can be selected and processed in one request.
- The result is one standalone HTML file with embedded thumbnails and local search.
- The current legacy detector/matcher is retained for the first integration pass. Its accuracy and support for current game layouts still need validation against representative screenshots.
- Cross-screenshot overlap deduplication is not enabled yet. It must be based on matching the same visual card location across overlapping screenshots, not just character ID, because a box can contain multiple copies of the same unit. Until that is implemented and tested, all detected cards are preserved rather than silently dropping legitimate duplicate copies.

## Project separation

This repository is independent from OPTC Farm Lite / Function Omega. Static reference data may be imported or refreshed as a copy, but there is no runtime connection to bot profiles, credentials, farming, or live game inventory.
