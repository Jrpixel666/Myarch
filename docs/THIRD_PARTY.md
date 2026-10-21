# Third-party code and licences

Myarch's own source is Apache-2.0 ([LICENSE](../LICENSE)). It bundles or fetches
the following.

## Fetched at build time into the APK

`tools/vendor-proot.py` downloads these from
[Termux's package repository](https://packages.termux.dev/apt/termux-main/) and
places them in `app/src/main/jniLibs/<abi>/`. They are **not committed** to this
repository. The exact versions, URLs and SHA-256 sums of every fetch are
recorded in `app/src/main/jniLibs/VENDORED.json`.

| Binary | Project | Licence | Source |
|---|---|---|---|
| `libproot.so`, `libproot-loader.so`, `libproot-loader32.so` | [proot](https://proot-me.github.io/) | GPL-2.0-or-later | [proot-me/proot](https://github.com/proot-me/proot); Termux's build recipe is in [termux/termux-packages](https://github.com/termux/termux-packages/tree/master/packages/proot) |
| `libtalloc.so` | [talloc](https://talloc.samba.org/) | LGPL-3.0-or-later | [samba.org](https://talloc.samba.org/), recipe in [termux-packages](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) |
| `libandroid-shmem.so` | [libandroid-shmem](https://github.com/termux/libandroid-shmem) | BSD-2-Clause | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem) |

### GPL compliance

proot is GPL-2.0-or-later. Myarch runs it as a **separate process** — it is
never linked into the app — so bundling it in the APK is aggregation rather than
a derived work. Anyone redistributing a built Myarch APK is nonetheless passing
on a GPL binary and must be able to supply its corresponding source. That is why
the vendoring script records the exact upstream package URL and checksum of
every binary it fetches: the sources for those exact builds are the ones linked
above, and Termux publishes both the recipes and the upstream tarballs.

Two ELF edits are made to the fetched binaries, both documented in
`tools/vendor-proot.py`: proot's `DT_NEEDED` for `libtalloc.so.2` is shortened
in place to `libtalloc.so`, and libtalloc's `DT_SONAME` likewise. This is
necessary because the APK packager only ships files matching `lib*.so`. No code
is changed.

## Vendored in this repository

| What | Where | Licence |
|---|---|---|
| Wayland protocol definitions | `tools/protocols/wayland.xml` | MIT (Expat) |
| wayland-protocols definitions | `tools/protocols/xdg-shell.xml`, `viewporter.xml`, `linux-dmabuf-v1.xml`, `fractional-scale-v1.xml`, `xdg-decoration-unstable-v1.xml`, `xdg-output-unstable-v1.xml`, `text-input-unstable-v3.xml` | MIT (Expat) |
| wlr-protocols definitions | `tools/protocols/wlr-layer-shell-unstable-v1.xml`, `wlr-foreign-toplevel-management-unstable-v1.xml` | MIT-like (per-file notices) |

The XML files carry their own copyright headers, which are preserved. They are
read by `tools/wlgen/wlgen.py` to generate `Protocols.kt`; the generated file
notes its sources.

## Downloaded onto the device, not into the APK

| What | Licence |
|---|---|
| The Arch Linux ARM rootfs and everything pacman installs | Per-package; Arch's own licensing applies |
| Quickshell, compiled from source on the device | LGPL-3.0 ([quickshell](https://quickshell.outfoxxed.me/)) |

## Palettes

The bundled themes reproduce the colour values of Tokyo Night, Catppuccin,
Gruvbox, Nord, Everforest, Rose Pine and Omarchy's Matte Black. Each upstream
project publishes its palette under a permissive licence (MIT in every case
here). No code or assets from those projects are included — only the colour
values, listed in `BuiltInThemes.kt`.

## Updating the vendored binaries

```sh
tools/vendor-proot.py          # refetches and rewrites VENDORED.json
tools/vendor-proot.py --check
```

Commit the updated `VENDORED.json` in a dedicated change, so the provenance of
each binary stays auditable.
