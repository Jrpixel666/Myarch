"""Command-line entry point for Forge.

    forge                 # start the GUI (serve + open a browser)
    forge serve           # start the GUI without opening a browser
    forge build "<idea>"  # one-shot headless build, prints the APK path
    forge doctor          # check the toolchain

Configuration comes from flags and these environment variables:
    ANTHROPIC_API_KEY   Claude key (absent → offline fallback)
    FORGE_MODEL         model id (default claude-opus-5-5)
    FORGE_OUT           where finished APKs are copied (default /tmp/forge-apks)
    FORGE_WORK          scratch build directory (default /tmp/forge-builds)
    ANDROID_HOME        Android SDK location
"""

from __future__ import annotations

import argparse
import os
import socket
import sys
import threading
import webbrowser
from pathlib import Path

from . import __version__, generator
from .server import Config, serve


def _config_from_args(args) -> Config:
    return Config(
        model=os.environ.get("FORGE_MODEL", generator.DEFAULT_MODEL),
        api_key=os.environ.get("ANTHROPIC_API_KEY"),
        force_offline=bool(getattr(args, "offline", False)),
        work_root=Path(os.environ.get("FORGE_WORK", "/tmp/forge-builds")),
        out_dir=Path(os.environ.get("FORGE_OUT", "/tmp/forge-apks")),
        sdk_dir=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"),
    )


def _lan_ip() -> str:
    """Best-effort LAN IP so a phone can be told where to point its browser."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def cmd_serve(args) -> int:
    config = _config_from_args(args)
    host = "0.0.0.0" if args.lan else args.host
    httpd = serve(config, host, args.port)

    local_url = f"http://127.0.0.1:{args.port}"
    print(f"Forge {__version__} serving on http://{host}:{args.port}")
    print(f"  Linux GUI:   {local_url}")
    if args.lan:
        print(f"  Phone GUI:   http://{_lan_ip()}:{args.port}  "
              f"(open this on your Android device, same Wi-Fi)")
    else:
        print("  (run with --lan to let your Android phone reach this GUI)")
    if config.force_offline:
        print("  Mode: OFFLINE (templates only, no Claude)")
    elif not config.api_key:
        print("  Mode: offline fallback (set ANTHROPIC_API_KEY for full generation)")
    else:
        print(f"  Mode: Claude ({config.model})")
    print("Press Ctrl+C to stop.")

    if args.open and not args.lan:
        threading.Timer(0.6, lambda: webbrowser.open(local_url)).start()

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
        httpd.shutdown()
    return 0


def cmd_build(args) -> int:
    config = _config_from_args(args)
    from . import builder
    spec = generator.generate(
        args.idea, model=config.model, api_key=config.api_key,
        force_offline=config.force_offline,
    )
    print(f"Generated “{spec.name}” via {spec.source} "
          f"(files: {', '.join(spec.files)}; location: {spec.needs_location})")
    if spec.notes:
        print(f"  note: {spec.notes}")
    if not builder.find_sdk_dir(config.sdk_dir):
        print("No Android SDK found — set ANDROID_HOME. Prepared project only.",
              file=sys.stderr)
        bd = builder.prepare_build_dir(spec, config.work_root / "oneshot",
                                       sdk_dir=config.sdk_dir)
        print(f"Build dir ready (unbuilt): {bd}")
        return 2
    result = builder.build(
        spec, work_root=config.work_root, out_dir=config.out_dir,
        sdk_dir=config.sdk_dir, log=lambda s: print("  " + s),
    )
    print(f"\nAPK: {result.apk_path}")
    return 0


def cmd_doctor(args) -> int:
    from .server import Forge
    checks = Forge(_config_from_args(args)).doctor()
    print("Forge toolchain check:")
    for key, value in checks.items():
        print(f"  {key:16} {value}")
    ready = checks["java"] and checks["android_sdk"] and checks["template"]
    print("\nReady to build APKs." if ready
          else "\nNot ready: install a JDK 17 + Android SDK (API 35). See forge/README.md.")
    return 0 if ready else 1


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="forge", description="Turn an idea into an Android APK.")
    p.add_argument("--version", action="version", version=f"Forge {__version__}")
    sub = p.add_subparsers(dest="cmd")

    def add_common(sp):
        sp.add_argument("--offline", action="store_true",
                        help="use the offline templates even if a Claude key is set")

    s = sub.add_parser("serve", help="run the GUI server")
    s.add_argument("--host", default="127.0.0.1")
    s.add_argument("--port", type=int, default=8787)
    s.add_argument("--lan", action="store_true",
                   help="bind 0.0.0.0 so your phone can reach the GUI")
    s.add_argument("--no-open", dest="open", action="store_false",
                   help="do not open a browser")
    add_common(s)
    s.set_defaults(func=cmd_serve, open=True)

    b = sub.add_parser("build", help="headless one-shot build")
    b.add_argument("idea")
    add_common(b)
    b.set_defaults(func=cmd_build)

    d = sub.add_parser("doctor", help="check the toolchain")
    add_common(d)
    d.set_defaults(func=cmd_doctor)

    return p


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "cmd", None):
        # Bare `forge` → serve + open browser with defaults.
        args = parser.parse_args(["serve"])
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
