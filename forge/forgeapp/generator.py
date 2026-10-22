"""Turn a plain-language idea into an :class:`AppSpec`.

Two paths:

* **Claude** (default when an API key is available and the ``anthropic`` SDK is
  installed) writes a small, self-contained web app for the idea.
* **Offline fallback** matches a few keywords to built-in templates so the
  whole pipeline works — and is testable — with no key and no network. It is
  deliberately limited; the user is told as much.

The output is always normalised and validated here, so the builder downstream
can trust it regardless of which path produced it.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Optional

# applicationId segments are Java package segments: lowercase, alphanumeric,
# each starting with a letter. We fix a stable prefix so generated apps never
# collide with Myarch or each other.
APP_ID_PREFIX = "dev.myarch.forge.gen"

LOCATION_HINTS = (
    "location",
    "near me",
    "nearby",
    "my position",
    "gps",
    "geolocation",
    "where am i",
    "around me",
    "distance to",
    "bus",
    "train",
    "transit",
    "commute",
    "weather",
    "nearest",
)

DEFAULT_MODEL = "claude-opus-5-5"


@dataclass
class AppSpec:
    """A fully-resolved description of one app, ready to build."""

    name: str
    app_id: str
    needs_location: bool
    files: dict[str, str]
    extra_permissions: list[str] = field(default_factory=list)
    source: str = "offline"  # "claude" | "offline"
    notes: str = ""

    def __post_init__(self) -> None:
        if "index.html" not in self.files:
            raise ValueError("an AppSpec must contain an index.html")


# --------------------------------------------------------------------------- #
# Naming helpers                                                              #
# --------------------------------------------------------------------------- #

def slugify(text: str) -> str:
    """``"Next Bus!"`` -> ``"next-bus"``; always a valid, non-empty slug."""
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug or "app"


def package_segment(text: str) -> str:
    """A single valid Java package segment derived from ``text``.

    Lowercase, alphanumeric only, must start with a letter. Empty or
    digit-leading inputs are prefixed so the result is always a legal segment.
    """
    seg = re.sub(r"[^a-z0-9]", "", text.lower())
    if not seg:
        return "app"
    if not seg[0].isalpha():
        seg = "a" + seg
    return seg


def derive_app_id(name: str) -> str:
    return f"{APP_ID_PREFIX}.{package_segment(name)}"


def title_from_idea(idea: str) -> str:
    """A short, human title guessed from the idea when the model gives none."""
    words = re.findall(r"[A-Za-z0-9]+", idea)
    if not words:
        return "My App"
    return " ".join(w.capitalize() for w in words[:4])


def infer_location(idea: str, files: dict[str, str]) -> bool:
    haystack = (idea + " " + " ".join(files.values())).lower()
    if "navigator.geolocation" in haystack:
        return True
    return any(h in haystack for h in LOCATION_HINTS)


# --------------------------------------------------------------------------- #
# Normalisation                                                               #
# --------------------------------------------------------------------------- #

def normalise_spec(
    *,
    idea: str,
    name: Optional[str],
    files: dict[str, str],
    needs_location: Optional[bool],
    extra_permissions: Optional[list[str]],
    source: str,
    notes: str = "",
) -> AppSpec:
    name = (name or title_from_idea(idea)).strip() or title_from_idea(idea)

    clean_files: dict[str, str] = {}
    for path, content in files.items():
        # Keep paths relative and inside the assets dir; reject traversal.
        p = path.strip().lstrip("/")
        if ".." in p.split("/") or not p:
            continue
        clean_files[p] = content
    if "index.html" not in clean_files:
        raise ValueError("generated app is missing index.html")

    loc = infer_location(idea, clean_files) if needs_location is None else needs_location

    perms = list(extra_permissions or [])
    if loc:
        for p in ("android.permission.ACCESS_FINE_LOCATION",
                  "android.permission.ACCESS_COARSE_LOCATION"):
            if p not in perms:
                perms.append(p)
    # Only ever allow android.permission.* here; drop anything else defensively.
    perms = [p for p in perms if p.startswith("android.permission.")]

    return AppSpec(
        name=name,
        app_id=derive_app_id(name),
        needs_location=loc,
        files=clean_files,
        extra_permissions=perms,
        source=source,
        notes=notes,
    )


# --------------------------------------------------------------------------- #
# Claude path                                                                 #
# --------------------------------------------------------------------------- #

SYSTEM_PROMPT = """You generate tiny, self-contained Android WebView apps from \
a one-line idea. The host wraps your files in a WebView that loads \
file:///android_asset/index.html, with JavaScript, DOM storage and \
geolocation already enabled.

Rules:
- Output a SINGLE JSON object and nothing else. No markdown fences, no prose.
- Shape: {"name": str, "needs_location": bool, "extra_permissions": [str], \
"files": {"<relative path>": "<file contents>", ...}}.
- "files" MUST include "index.html". You may add "style.css", "app.js", etc.;
  reference them with relative paths from index.html.
- Everything is offline-first: no build step, no npm, no frameworks that need a
  bundler. Plain HTML/CSS/JS, or a CDN <script> if truly needed.
- Make it genuinely work on a phone: responsive, dark-mode friendly, large tap
  targets. Use navigator.geolocation when the idea is location-based, and set
  needs_location accordingly.
- "extra_permissions" lists only android.permission.* strings beyond INTERNET
  (which is always granted). Location permissions are added automatically when
  needs_location is true, so you need not list them.
- Prefer real, key-free data sources. When an idea truly needs an API key the
  user must supply, add a clearly-marked settings field in the UI that stores
  the key in localStorage; never hardcode secrets.
- Keep it small and correct over elaborate and broken.
"""


def _extract_json(text: str) -> dict:
    """Pull the first complete JSON object out of the model's reply."""
    text = text.strip()
    # Strip accidental ```json fences.
    if text.startswith("```"):
        text = re.sub(r"^```[a-zA-Z]*\n", "", text)
        text = re.sub(r"\n```$", "", text).strip()
    start = text.find("{")
    if start == -1:
        raise ValueError("no JSON object in model reply")
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(text)):
        c = text[i]
        if in_str:
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
        else:
            if c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return json.loads(text[start:i + 1])
    raise ValueError("unterminated JSON object in model reply")


def generate_with_claude(idea: str, *, model: str, api_key: Optional[str]) -> AppSpec:
    """Generate via the Anthropic SDK. Raises on any failure (caller falls back)."""
    from anthropic import Anthropic  # imported lazily; optional dependency

    client = Anthropic(api_key=api_key) if api_key else Anthropic()

    # Stream because the HTML/JS output can be long; adaptive thinking is the
    # default on claude-opus-5-5 when `thinking` is omitted.
    with client.messages.stream(
        model=model,
        max_tokens=16000,
        system=SYSTEM_PROMPT,
        messages=[{"role": "user", "content": f"App idea: {idea}"}],
    ) as stream:
        message = stream.get_final_message()

    text = "".join(
        block.text for block in message.content if getattr(block, "type", None) == "text"
    )
    data = _extract_json(text)

    files = data.get("files")
    if not isinstance(files, dict) or not files:
        raise ValueError("model returned no files")
    files = {str(k): str(v) for k, v in files.items()}

    return normalise_spec(
        idea=idea,
        name=data.get("name"),
        files=files,
        needs_location=bool(data["needs_location"]) if "needs_location" in data else None,
        extra_permissions=[str(p) for p in data.get("extra_permissions", [])],
        source="claude",
    )


# --------------------------------------------------------------------------- #
# Offline fallback                                                            #
# --------------------------------------------------------------------------- #

def _page(title: str, body: str, script: str = "") -> str:
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>{title}</title>
<style>
  :root {{ color-scheme: dark light; --bg:#0d1117; --fg:#e6edf3; --accent:#4c8bf5; --card:#161b22; }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
         background:var(--bg); color:var(--fg); padding:max(16px,env(safe-area-inset-top)) 16px 24px; }}
  h1 {{ font-size:1.5rem; margin:.2em 0 .6em; }}
  .card {{ background:var(--card); border:1px solid #30363d; border-radius:14px; padding:18px; margin:14px 0; }}
  button {{ font:inherit; font-weight:600; background:var(--accent); color:#fff; border:0;
            border-radius:12px; padding:14px 18px; width:100%; }}
  button:active {{ opacity:.8; }}
  a {{ color:var(--accent); }}
  .muted {{ opacity:.7; font-size:.9rem; }}
  pre {{ white-space:pre-wrap; word-break:break-word; }}
</style>
</head>
<body>
<h1>{title}</h1>
{body}
<p class="muted">Generated offline by Forge. Connect a Claude API key for
full, idea-specific generation.</p>
<script>{script}</script>
</body>
</html>
"""


def _offline_location_app(title: str) -> dict[str, str]:
    body = """
<div class="card">
  <p>Find transit and places near where you are right now.</p>
  <button id="go">Use my location</button>
  <p id="out" class="muted">Tap the button to locate yourself.</p>
</div>
"""
    script = r"""
const out = document.getElementById('out');
document.getElementById('go').onclick = () => {
  if (!navigator.geolocation) { out.textContent = 'Geolocation not available.'; return; }
  out.textContent = 'Locating…';
  navigator.geolocation.getCurrentPosition(
    p => {
      const { latitude: la, longitude: lo } = p.coords;
      out.innerHTML = `You are near <b>${la.toFixed(5)}, ${lo.toFixed(5)}</b>.<br>` +
        `<a href="https://www.google.com/maps/search/bus+stop/@${la},${lo},15z">Bus stops near me</a><br>` +
        `<a href="https://www.google.com/maps/dir/?api=1&origin=${la},${lo}&travelmode=transit&destination=">Transit directions</a>`;
    },
    e => { out.textContent = 'Could not get location: ' + e.message; },
    { enableHighAccuracy: true, timeout: 10000 }
  );
};
"""
    return {"index.html": _page(title, body, script)}


def _offline_timer_app(title: str) -> dict[str, str]:
    body = """
<div class="card">
  <p>Countdown timer.</p>
  <input id="secs" type="number" value="60" min="1"
         style="width:100%;padding:12px;border-radius:10px;border:1px solid #30363d;background:#0d1117;color:inherit;font:inherit">
  <p></p>
  <button id="start">Start</button>
  <h1 id="clock" style="text-align:center;font-variant-numeric:tabular-nums">00:00</h1>
</div>
"""
    script = r"""
let t = null;
const clock = document.getElementById('clock');
function render(s){ const m=Math.floor(s/60); clock.textContent =
  String(m).padStart(2,'0')+':'+String(s%60).padStart(2,'0'); }
document.getElementById('start').onclick = () => {
  if (t) clearInterval(t);
  let s = Math.max(1, parseInt(document.getElementById('secs').value||'0',10));
  render(s);
  t = setInterval(() => { s--; render(Math.max(0,s)); if (s<=0){ clearInterval(t); t=null; } }, 1000);
};
"""
    return {"index.html": _page(title, body, script)}


def _offline_note_app(title: str) -> dict[str, str]:
    body = """
<div class="card">
  <p>A quick note that stays on this device.</p>
  <textarea id="note" rows="10" placeholder="Type here…"
    style="width:100%;padding:12px;border-radius:10px;border:1px solid #30363d;background:#0d1117;color:inherit;font:inherit"></textarea>
  <p id="saved" class="muted">Saved automatically.</p>
</div>
"""
    script = r"""
const n = document.getElementById('note'), saved = document.getElementById('saved');
try { n.value = localStorage.getItem('forge.note') || ''; } catch(e){}
n.addEventListener('input', () => {
  try { localStorage.setItem('forge.note', n.value); saved.textContent = 'Saved ' + new Date().toLocaleTimeString(); }
  catch(e){ saved.textContent = 'Could not save on this device.'; }
});
"""
    return {"index.html": _page(title, body, script)}


def _offline_generic_app(title: str, idea: str) -> dict[str, str]:
    body = f"""
<div class="card">
  <p>This is a starter shell for your idea:</p>
  <pre>{idea}</pre>
  <p class="muted">The offline generator produced a placeholder. Add a Claude
  API key and rebuild to get an app written specifically for this idea.</p>
</div>
"""
    return {"index.html": _page(title, body)}


def generate_offline(idea: str) -> AppSpec:
    title = title_from_idea(idea)
    low = idea.lower()
    if any(h in low for h in ("bus", "transit", "train", "near me", "nearby",
                              "location", "commute", "nearest", "around me")):
        files, note = _offline_location_app(title), "Offline location template."
    elif any(h in low for h in ("timer", "countdown", "stopwatch", "pomodoro", "alarm")):
        files, note = _offline_timer_app(title), "Offline timer template."
    elif any(h in low for h in ("note", "todo", "to-do", "list", "memo", "journal", "diary")):
        files, note = _offline_note_app(title), "Offline note template."
    else:
        files, note = _offline_generic_app(title, idea), "Offline placeholder (no matching template)."
    return normalise_spec(
        idea=idea, name=title, files=files,
        needs_location=None, extra_permissions=None,
        source="offline", notes=note,
    )


# --------------------------------------------------------------------------- #
# Public entry point                                                          #
# --------------------------------------------------------------------------- #

def generate(
    idea: str,
    *,
    model: str = DEFAULT_MODEL,
    api_key: Optional[str] = None,
    force_offline: bool = False,
) -> AppSpec:
    """Generate an :class:`AppSpec` for ``idea``.

    Uses Claude when a key is available and the SDK is importable; otherwise, or
    on any Claude error, falls back to the offline templates. The caller can see
    which path ran via :attr:`AppSpec.source`.
    """
    idea = idea.strip()
    if not idea:
        raise ValueError("idea must not be empty")
    if force_offline:
        return generate_offline(idea)
    try:
        import anthropic  # noqa: F401
    except ImportError:
        return generate_offline(idea)
    try:
        return generate_with_claude(idea, model=model, api_key=api_key)
    except Exception as exc:  # noqa: BLE001 — any failure degrades gracefully
        spec = generate_offline(idea)
        spec.notes = f"Claude generation failed ({exc}); used offline fallback."
        return spec
