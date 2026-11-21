"""Export static character reference records from a Farm Lite sakura.db file.

This utility reads only the MstCharacter_ master table. It never reads account,
inventory, session, or saved-profile data.
"""
import argparse
import json
import sqlite3
from pathlib import Path


ATTRIBUTES = {1: "STR", 2: "DEX", 3: "QCK", 4: "PSY", 5: "INT"}
CLASSES = {
    1: "FIGHTER",
    2: "SLASHER",
    3: "STRIKER",
    4: "SHOOTER",
    5: "FREE SPIRIT",
    6: "DRIVEN",
    7: "CEREBRAL",
    8: "POWERHOUSE",
}


def _value(row, key, default=None):
    value = row[key] if key in row.keys() else default
    return default if value is None else value


def export_characters(database_path, output_path):
    database_path = Path(database_path)
    if not database_path.is_file():
        raise FileNotFoundError("Farm Lite SQLite database not found: {0}".format(database_path))

    required = {
        "serverId_", "name_", "attributeId_", "subAttributeId_",
        "characterType_", "subCharacterType_", "rarity_", "isRarityPlus_",
    }
    with sqlite3.connect(str(database_path)) as connection:
        connection.row_factory = sqlite3.Row
        tables = connection.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            ("MstCharacter_",),
        ).fetchone()
        if tables is None:
            raise ValueError("The supplied SQLite file has no MstCharacter_ table.")

        columns = {
            row["name"] for row in connection.execute(
                'PRAGMA table_info("MstCharacter_")'
            )
        }
        missing = sorted(required - columns)
        if missing:
            raise ValueError(
                "MstCharacter_ is missing required columns: {0}".format(", ".join(missing))
            )

        rows = connection.execute(
            'SELECT * FROM "MstCharacter_" ORDER BY "serverId_"'
        ).fetchall()

    characters = []
    seen_ids = set()
    for row in rows:
        server_id = int(row["serverId_"])
        if server_id in seen_ids:
            raise ValueError("Duplicate serverId_ in MstCharacter_: {0}".format(server_id))
        seen_ids.add(server_id)

        rarity = int(_value(row, "rarity_", 0))
        if rarity <= 0:
            continue

        attribute = ATTRIBUTES.get(int(_value(row, "attributeId_", -1)), "")
        sub_attribute_id = int(_value(row, "subAttributeId_", -1))
        sub_attribute = ATTRIBUTES.get(sub_attribute_id, "")
        type_label = " / ".join(value for value in (attribute, sub_attribute) if value)

        class_one = CLASSES.get(int(_value(row, "characterType_", -1)), "")
        class_two = CLASSES.get(int(_value(row, "subCharacterType_", -1)), "")
        classes = []
        for value in (class_one, class_two):
            if value and value not in classes:
                classes.append(value)

        rarity_label = str(rarity) + "★"
        if int(_value(row, "isRarityPlus_", 0)) == 1:
            rarity_label += "+"

        characters.append({
            "id": server_id,
            "name": str(_value(row, "name_", "Unknown")),
            "type": type_label,
            "classes": classes,
            "rarity": rarity,
            "stars": rarity_label,
            "limit_exp": _value(row, "limitExp_", None),
        })

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema_version": 1,
        "source": "Farm Lite MstCharacter_ static master table",
        "character_count": len(characters),
        "characters": characters,
    }
    output_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return len(characters)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", required=True, help="Path to Farm Lite sakura.db")
    parser.add_argument(
        "--output", default="data/farm_lite_characters.json",
        help="Output JSON path (default: data/farm_lite_characters.json)",
    )
    args = parser.parse_args()
    count = export_characters(args.database, args.output)
    print("Exported {0} character reference records to {1}".format(count, args.output))


if __name__ == "__main__":
    main()
