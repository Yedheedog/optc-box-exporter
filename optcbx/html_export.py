"""Create a self-contained HTML character box export."""

from html import escape
from typing import Iterable, Mapping


def build_box_html(entries: Iterable[Mapping[str, object]]) -> str:
    """Build one offline HTML document; all detected thumbnails are embedded."""
    cards = []
    for index, entry in enumerate(entries, start=1):
        name = escape(str(entry.get("name") or "Unknown character"))
        number = escape(str(entry.get("number") or ""))
        type_name = escape(str(entry.get("type_") or ""))
        classes = entry.get("class_") or []
        if isinstance(classes, str):
            classes = [classes]
        class_text = escape(", ".join(str(value) for value in classes))
        stars = escape(str(entry.get("stars") or ""))
        image = str(entry.get("thumbnail") or "")
        if not image.startswith("data:image/"):
            image = ""
        cards.append(
            '<article class="card" data-search="{search}">'
            '<img loading="lazy" src="{image}" alt="{name}">'
            '<div class="details"><strong>{name}</strong>'
            '<span>#{number} · {type_name} · {stars}★</span>'
            '<small>{class_text}</small></div></article>'.format(
                search=escape(" ".join([name, number, type_name, class_text]).lower(), quote=True),
                image=escape(image, quote=True), name=name, number=number,
                type_name=type_name, stars=stars, class_text=class_text
            )
        )

    card_markup = "\n".join(cards)
    total = len(cards)
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>OPTC Character Box</title>
<style>
:root{color-scheme:dark;font-family:system-ui,-apple-system,Segoe UI,sans-serif}
*{box-sizing:border-box}body{margin:0;background:#111827;color:#f9fafb}
header{position:sticky;top:0;z-index:2;padding:1rem;background:#1f2937;border-bottom:1px solid #374151}
h1{font-size:1.35rem;margin:0 0 .35rem}#summary{color:#cbd5e1;font-size:.9rem}
input{width:min(100%,34rem);padding:.7rem .85rem;margin-top:.8rem;border:1px solid #64748b;border-radius:.6rem;background:#0f172a;color:white}
main{padding:1rem}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(145px,1fr));gap:.8rem}
.card{overflow:hidden;border:1px solid #374151;border-radius:.7rem;background:#1f2937}
.card img{display:block;width:100%;aspect-ratio:1;object-fit:contain;background:#0b1220}
.details{display:grid;gap:.25rem;padding:.6rem}.details strong{font-size:.9rem;line-height:1.25}
.details span,.details small{font-size:.75rem;color:#cbd5e1}
.empty{color:#cbd5e1;padding:2rem;text-align:center}
</style>
</head>
<body>
<header><h1>OPTC Character Box</h1>
<div id="summary">""" + str(total) + """ detected character cards</div>
<input id="search" type="search" placeholder="Search name, ID, type or class" aria-label="Search characters">
</header>
<main><section class="grid" id="grid">""" + card_markup + """</section>
<div class="empty" id="empty" hidden>No matching characters.</div></main>
<script>
const search=document.getElementById('search');
const cards=[...document.querySelectorAll('.card')];
const summary=document.getElementById('summary');
const empty=document.getElementById('empty');
search.addEventListener('input',()=>{
 const q=search.value.trim().toLowerCase();let shown=0;
 cards.forEach(card=>{const match=card.dataset.search.includes(q);card.hidden=!match;if(match)shown++;});
 summary.textContent=shown+' of '+cards.length+' character cards';
 empty.hidden=shown!==0;
});
</script>
</body></html>"""
