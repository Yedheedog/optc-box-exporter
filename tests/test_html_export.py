from optcbx.html_export import build_box_html


def test_html_export_is_self_contained_and_searchable():
    document = build_box_html([{
        "name": "Monkey D. Luffy",
        "number": 1,
        "type_": "STR",
        "class_": ["FIGHTER", "FREE SPIRIT"],
        "stars": "6",
        "thumbnail": "data:image/jpeg;base64,AA==",
    }])

    assert "<title>OPTC Character Box</title>" in document
    assert "1 detected character cards" in document
    assert 'src="data:image/jpeg;base64,AA=="' in document
    assert "Monkey D. Luffy" in document
    assert "FREE SPIRIT" in document
    assert "cdn." not in document
    assert "querySelectorAll('.card')" in document


def test_html_export_escapes_character_metadata():
    document = build_box_html([{
        "name": '<script>alert("x")</script>',
        "number": 7,
        "type_": "INT",
        "class_": [],
        "stars": "5",
        "thumbnail": "data:image/jpeg;base64,AA==",
    }])

    assert "<script>alert" not in document
    assert "&lt;script&gt;" in document
