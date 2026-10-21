#!/usr/bin/env bash
# Compiles an XKB keymap for the compositor to hand to clients.
#
# Myarch ships a compact fallback keymap in the APK, but it only covers a US
# layout. Once xkeyboard-config is installed, the real thing is better: it
# supports every layout and the compose key.
set -euo pipefail

layout="${1:-us}"
variant="${2:-}"
out="${XDG_RUNTIME_DIR:-/run/user/0}/keymap.xkb"

mkdir -p "$(dirname "$out")"

if ! command -v xkbcli >/dev/null 2>&1; then
    echo "[myarch] xkbcli is not installed; install libxkbcommon" >&2
    exit 1
fi

if [ -n "$variant" ]; then
    xkbcli compile-keymap --layout "$layout" --variant "$variant" > "$out"
else
    xkbcli compile-keymap --layout "$layout" > "$out"
fi

echo "[myarch] wrote $out ($(wc -c < "$out") bytes, layout $layout${variant:+ variant $variant})"
