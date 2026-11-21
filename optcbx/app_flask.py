import base64
import binascii
import io

from flask import Flask, Response, jsonify, render_template, request
from PIL import Image, UnidentifiedImageError
import numpy as np

import optcbx
from optcbx.html_export import build_box_html

app = Flask(__name__, static_folder="static", template_folder="templates")
app.config["MAX_CONTENT_LENGTH"] = 100 * 1024 * 1024


@app.route("/")
def index():
    return render_template("index.html")


@app.route("/export", methods=["POST"])
def export():
    """Recognize cards in each uploaded screenshot and return one offline HTML file."""
    payload = request.get_json(silent=True) or {}
    images = payload.get("images")
    if images is None and payload.get("image"):
        images = [payload["image"]]
    if not isinstance(images, list) or not images:
        return jsonify({"error": "Upload at least one screenshot."}), 400
    if len(images) > 100:
        return jsonify({"error": "Please upload no more than 100 screenshots at once."}), 400

    image_size = payload.get("imageSize", 64)
    try:
        image_size = int(image_size)
        if image_size < 32 or image_size > 128:
            raise ValueError
    except (TypeError, ValueError):
        return jsonify({"error": "Image size must be between 32 and 128."}), 400

    entries = []
    for screenshot_index, encoded in enumerate(images, start=1):
        if not isinstance(encoded, str):
            return jsonify({"error": "Each screenshot must be a base64 image."}), 400
        try:
            raw = base64.b64decode(encoded, validate=True)
            source = Image.open(io.BytesIO(raw)).convert("RGB")
        except (binascii.Error, UnidentifiedImageError, ValueError):
            return jsonify({"error": "Screenshot {0} is not a valid image.".format(screenshot_index)}), 400

        pixels = np.asarray(source)[:, :, ::-1].copy()
        try:
            characters, thumbnails = optcbx.find_characters_from_screenshot(
                pixels, image_size, return_thumbnails=True
            )
        except Exception as exc:
            app.logger.exception("Screenshot recognition failed")
            return jsonify({
                "error": "Could not recognize screenshot {0}: {1}".format(
                    screenshot_index, str(exc)
                )
            }), 422

        for character, thumbnail in zip(characters, thumbnails):
            thumb = Image.fromarray(np.asarray(thumbnail)[:, :, ::-1])
            buffer = io.BytesIO()
            thumb.save(buffer, format="JPEG", quality=88)
            data_uri = "data:image/jpeg;base64," + base64.b64encode(
                buffer.getvalue()
            ).decode("ascii")
            entry = dict(character._asdict())
            entry["thumbnail"] = data_uri
            entry["source_screenshot"] = screenshot_index
            entries.append(entry)

    if not entries:
        return jsonify({
            "error": "No character cards were detected. Try clearer screenshots or check the detection model."
        }), 422

    document = build_box_html(entries)
    return Response(
        document,
        mimetype="text/html",
        headers={"Content-Disposition": 'attachment; filename="optc-character-box.html"'}
    )
