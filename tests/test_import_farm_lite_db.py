import json
import sqlite3

import pytest

from tools.import_farm_lite_db import export_characters


def test_exports_only_static_character_master_fields(tmp_path):
    db_path = tmp_path / "sakura.db"
    output_path = tmp_path / "characters.json"
    with sqlite3.connect(str(db_path)) as connection:
        connection.execute("""
            CREATE TABLE MstCharacter_ (
                serverId_ INTEGER, name_ TEXT, attributeId_ INTEGER,
                subAttributeId_ INTEGER, characterType_ INTEGER,
                subCharacterType_ INTEGER, rarity_ INTEGER,
                isRarityPlus_ INTEGER, limitExp_ INTEGER
            )
        """)
        connection.execute(
            "INSERT INTO MstCharacter_ VALUES (1001,'Luffy',1,2,1,5,6,1,100)"
        )
        connection.execute(
            "INSERT INTO MstCharacter_ VALUES (1002,'Berry',0,-1,-1,-1,0,0,0)"
        )

    assert export_characters(db_path, output_path) == 1
    payload = json.loads(output_path.read_text(encoding="utf-8"))
    assert payload["characters"][0] == {
        "id": 1001,
        "name": "Luffy",
        "type": "STR / DEX",
        "classes": ["FIGHTER", "FREE SPIRIT"],
        "rarity": 6,
        "stars": "6★+",
        "limit_exp": 100,
    }
    assert "inventory" not in payload
    assert "account" not in payload


def test_rejects_database_without_character_table(tmp_path):
    db_path = tmp_path / "empty.db"
    sqlite3.connect(str(db_path)).close()
    with pytest.raises(ValueError, match="MstCharacter_"):
        export_characters(db_path, tmp_path / "out.json")
