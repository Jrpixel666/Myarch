#!/usr/bin/env python3
"""Generate Kotlin protocol tables for the Myarch Wayland compositor.

The Wayland wire format is self-describing only in length and opcode; the
argument layout of every message has to be known ahead of time by both peers.
Rather than hand-transcribe those layouts (and get them subtly wrong) this
script reads the upstream protocol XML and emits a Kotlin table the server
uses to decode requests and encode events.

Usage:
    tools/wlgen/wlgen.py \
        --out wayland/src/main/kotlin/dev/myarch/wayland/protocol/Protocols.kt \
        tools/protocols/*.xml
"""
from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ARG_TYPES = {
    "int": "INT",
    "uint": "UINT",
    "fixed": "FIXED",
    "string": "STRING",
    "object": "OBJECT",
    "new_id": "NEW_ID",
    "array": "ARRAY",
    "fd": "FD",
}


def camel(name: str) -> str:
    return "".join(part.capitalize() for part in name.split("_"))


def screaming(name: str) -> str:
    # wl_surface -> WL_SURFACE ; set_window_geometry -> SET_WINDOW_GEOMETRY
    out = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return out.upper().replace("-", "_")


def kt_str(value: str | None) -> str:
    if value is None:
        return "null"
    escaped = value.replace("\\", "\\\\").replace('"', '\\"')
    return f'"{escaped}"'


class Arg:
    def __init__(self, el: ET.Element) -> None:
        self.name = el.get("name") or ""
        raw = el.get("type") or ""
        if raw not in ARG_TYPES:
            raise SystemExit(f"unknown argument type {raw!r} on {self.name!r}")
        self.type = ARG_TYPES[raw]
        self.interface = el.get("interface")
        self.nullable = (el.get("allow-null") == "true")
        self.enum = el.get("enum")

    def render(self) -> str:
        return (
            f"Arg({kt_str(self.name)}, ArgType.{self.type}, "
            f"{kt_str(self.interface)}, {str(self.nullable).lower()})"
        )


class Message:
    def __init__(self, el: ET.Element, opcode: int) -> None:
        self.name = el.get("name") or ""
        self.opcode = opcode
        self.since = int(el.get("since") or 1)
        self.destructor = (el.get("type") == "destructor")
        self.args = [Arg(a) for a in el.findall("arg")]

    def render(self) -> str:
        args = ", ".join(a.render() for a in self.args)
        return (
            f"            Message({kt_str(self.name)}, {self.opcode}, {self.since}, "
            f"{str(self.destructor).lower()}, listOf({args}))"
        )


class EnumEntry:
    def __init__(self, el: ET.Element) -> None:
        self.name = el.get("name") or ""
        self.value = el.get("value") or "0"


class Enum:
    def __init__(self, el: ET.Element) -> None:
        self.name = el.get("name") or ""
        self.entries = [EnumEntry(e) for e in el.findall("entry")]


class Interface:
    def __init__(self, el: ET.Element) -> None:
        self.name = el.get("name") or ""
        self.version = int(el.get("version") or 1)
        self.requests = [Message(m, i) for i, m in enumerate(el.findall("request"))]
        self.events = [Message(m, i) for i, m in enumerate(el.findall("event"))]
        self.enums = [Enum(e) for e in el.findall("enum")]

    @property
    def object_name(self) -> str:
        return camel(self.name)

    def render_spec(self) -> str:
        reqs = ",\n".join(m.render() for m in self.requests)
        evts = ",\n".join(m.render() for m in self.events)
        return (
            f"    public val SPEC: Interface = Interface(\n"
            f"        name = NAME,\n"
            f"        version = VERSION,\n"
            f"        requests = listOf(\n{reqs}\n        ),\n"
            f"        events = listOf(\n{evts}\n        ),\n"
            f"    )\n"
        )

    def render(self) -> str:
        lines: list[str] = []
        lines.append(f"/** Generated from the `{self.name}` interface. */")
        lines.append(f"public object {self.object_name} {{")
        lines.append(f'    public const val NAME: String = "{self.name}"')
        lines.append(f"    public const val VERSION: Int = {self.version}")
        if self.requests:
            lines.append("")
            lines.append("    /** Opcodes of requests the client may send to us. */")
            lines.append("    public object Request {")
            for m in self.requests:
                lines.append(f"        public const val {screaming(m.name)}: Int = {m.opcode}")
            lines.append("    }")
        if self.events:
            lines.append("")
            lines.append("    /** Opcodes of events we may send to the client. */")
            lines.append("    public object Event {")
            for m in self.events:
                lines.append(f"        public const val {screaming(m.name)}: Int = {m.opcode}")
            lines.append("    }")
        for en in self.enums:
            if not en.entries:
                continue
            lines.append("")
            lines.append(f"    public object {camel(en.name)} {{")
            for entry in en.entries:
                ident = screaming(entry.name)
                if ident[0].isdigit():
                    ident = "V" + ident
                lines.append(f"        public const val {ident}: Int = {entry.value}")
            lines.append("    }")
        lines.append("")
        lines.append(self.render_spec())
        lines.append("}")
        return "\n".join(lines)


HEADER = """// Generated by tools/wlgen/wlgen.py -- DO NOT EDIT BY HAND.
//
// Source protocols (see tools/protocols/ and tools/protocols/LICENSE):
{sources}
//
// Regenerate with:
//     tools/wlgen/wlgen.py --out {out} tools/protocols/*.xml
@file:Suppress("MagicNumber", "LongMethod", "MaxLineLength", "ObjectPropertyNaming")

package dev.myarch.wayland.protocol

import dev.myarch.wayland.wire.Arg
import dev.myarch.wayland.wire.ArgType
import dev.myarch.wayland.wire.Interface
import dev.myarch.wayland.wire.Message
"""


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("xml", nargs="+")
    ns = ap.parse_args()

    interfaces: list[Interface] = []
    sources: list[str] = []
    for path in sorted(ns.xml):
        tree = ET.parse(path)
        root = tree.getroot()
        sources.append(f"//   - {Path(path).name} (protocol {root.get('name')})")
        for el in root.findall("interface"):
            interfaces.append(Interface(el))

    seen: dict[str, Interface] = {}
    for iface in interfaces:
        if iface.name in seen:
            raise SystemExit(f"duplicate interface {iface.name}")
        seen[iface.name] = iface

    body = [HEADER.format(sources="\n".join(sources), out=ns.out)]
    for iface in interfaces:
        body.append(iface.render())

    registry = ["/** Every interface Myarch knows how to speak, keyed by wire name. */",
                "public object Protocols {",
                "    public val byName: Map<String, Interface> = listOf(" ]
    registry.append(
        ",\n".join(f"        {i.object_name}.SPEC" for i in interfaces)
    )
    registry.append("    ).associateBy { it.name }")
    registry.append("")
    registry.append("    public operator fun get(name: String): Interface? = byName[name]")
    registry.append("}")
    body.append("\n".join(registry))

    out = Path(ns.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n\n".join(body) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(interfaces)} interfaces)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
