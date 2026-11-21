const fileInput = document.getElementById("screenshot-files");
const exportButton = document.getElementById("export-btn");
const preview = document.getElementById("screenshot-preview");
const statusText = document.getElementById("export-status");
const sizeSlider = document.getElementById("image-size-slider");

sizeSlider.addEventListener("input", () => {
  document.getElementById("image-size-value").textContent =
    sizeSlider.value + " × " + sizeSlider.value;
});

fileInput.addEventListener("change", () => {
  preview.replaceChildren();
  const files = Array.from(fileInput.files || []);
  files.forEach((file) => {
    if (!file.type.startsWith("image/")) return;
    const item = document.createElement("div");
    item.className = "preview-item";
    const image = document.createElement("img");
    image.alt = file.name;
    image.src = URL.createObjectURL(file);
    const label = document.createElement("span");
    label.textContent = file.name;
    item.append(image, label);
    preview.append(item);
  });
  exportButton.disabled = files.length === 0;
  statusText.textContent = files.length + " screenshot(s) selected.";
});

function readAsBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result).split(",")[1]);
    reader.onerror = () => reject(new Error("Could not read " + file.name));
    reader.readAsDataURL(file);
  });
}

exportButton.addEventListener("click", async () => {
  const files = Array.from(fileInput.files || []);
  if (!files.length) return;
  exportButton.disabled = true;
  statusText.textContent = "Reading screenshots…";
  try {
    const images = await Promise.all(files.map(readAsBase64));
    statusText.textContent = "Recognizing cards in " + images.length + " screenshot(s)…";
    const response = await fetch("/export", {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({images, imageSize: Number(sizeSlider.value)})
    });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      throw new Error(error.error || "Export failed (" + response.status + ").");
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "optc-character-box.html";
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    statusText.textContent = "HTML export created successfully.";
  } catch (error) {
    statusText.textContent = error.message || "An unexpected error occurred.";
  } finally {
    exportButton.disabled = false;
  }
});
