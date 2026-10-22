"""A tiny stdlib HTTP server that is both the Linux GUI and the Android GUI.

It serves a single responsive web page (``web/index.html``) and a small JSON
API. Builds run in background threads and expose a live log that the page polls,
so "type an idea → watch it build → download the APK" works identically in a
desktop browser and on a phone pointed at the same host.

No third-party web framework: just ``http.server``.
"""

from __future__ import annotations

import json
import threading
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Optional

from . import __version__
from . import generator
from . import builder

WEB_DIR = Path(__file__).resolve().parent / "web"


@dataclass
class Config:
    model: str = generator.DEFAULT_MODEL
    api_key: Optional[str] = None
    force_offline: bool = False
    work_root: Path = Path("/tmp/forge-builds")
    out_dir: Path = Path("/tmp/forge-apks")
    sdk_dir: Optional[str] = None


@dataclass
class Job:
    id: str
    idea: str
    state: str = "pending"  # pending|generating|building|done|error
    log: list[str] = field(default_factory=list)
    error: Optional[str] = None
    app_name: Optional[str] = None
    source: Optional[str] = None
    notes: Optional[str] = None
    apk_name: Optional[str] = None
    apk_path: Optional[str] = None
    seconds: Optional[float] = None
    lock: threading.Lock = field(default_factory=threading.Lock)

    def append(self, line: str) -> None:
        with self.lock:
            self.log.append(line)

    def snapshot(self) -> dict:
        with self.lock:
            return {
                "id": self.id,
                "idea": self.idea,
                "state": self.state,
                "log": list(self.log),
                "error": self.error,
                "app_name": self.app_name,
                "source": self.source,
                "notes": self.notes,
                "apk_name": self.apk_name,
                "seconds": self.seconds,
                "download": f"/download/{self.id}" if self.state == "done" else None,
            }


class Forge:
    """Owns configuration and the job registry."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self.jobs: dict[str, Job] = {}
        self._lock = threading.Lock()

    def start_build(self, idea: str) -> Job:
        job = Job(id=uuid.uuid4().hex[:12], idea=idea)
        with self._lock:
            self.jobs[job.id] = job
        threading.Thread(target=self._run, args=(job,), daemon=True).start()
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self.jobs.get(job_id)

    def _run(self, job: Job) -> None:
        cfg = self.config
        try:
            job.state = "generating"
            job.append(f"Generating app for: {job.idea}")
            spec = generator.generate(
                job.idea,
                model=cfg.model,
                api_key=cfg.api_key,
                force_offline=cfg.force_offline,
            )
            job.app_name = spec.name
            job.source = spec.source
            job.notes = spec.notes
            job.append(f"Generated “{spec.name}” via {spec.source}. "
                       f"Files: {', '.join(spec.files)}. "
                       f"Location: {'yes' if spec.needs_location else 'no'}.")
            if spec.notes:
                job.append(spec.notes)

            job.state = "building"
            result = builder.build(
                spec,
                work_root=cfg.work_root,
                out_dir=cfg.out_dir,
                sdk_dir=cfg.sdk_dir,
                log=job.append,
            )
            job.apk_path = str(result.apk_path)
            job.apk_name = result.apk_path.name
            job.seconds = result.seconds
            job.state = "done"
        except Exception as exc:  # noqa: BLE001 — surface any failure to the UI
            job.error = str(exc)
            job.append(f"ERROR: {exc}")
            job.state = "error"

    def doctor(self) -> dict:
        import shutil
        sdk = builder.find_sdk_dir(self.config.sdk_dir)
        checks = {
            "java": bool(shutil.which("java")),
            "keytool": bool(shutil.which("keytool")),
            "android_sdk": sdk,
            "template": builder.TEMPLATE_DIR.is_dir(),
        }
        try:
            import anthropic  # noqa: F401
            checks["anthropic_sdk"] = True
        except ImportError:
            checks["anthropic_sdk"] = False
        checks["api_key"] = bool(self.config.api_key)
        checks["force_offline"] = self.config.force_offline
        checks["model"] = self.config.model
        return checks


def make_handler(forge: Forge):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"Forge/{__version__}"

        def log_message(self, *args):  # quieter default logging
            pass

        # -- helpers ----------------------------------------------------- #
        def _send_json(self, obj, status=200):
            body = json.dumps(obj).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _send_bytes(self, data: bytes, content_type: str, filename=None):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(data)))
            if filename:
                self.send_header("Content-Disposition",
                                 f'attachment; filename="{filename}"')
            self.end_headers()
            self.wfile.write(data)

        # -- routes ------------------------------------------------------ #
        def do_GET(self):
            path = self.path.split("?", 1)[0]
            if path == "/":
                self._serve_index()
            elif path == "/api/doctor":
                self._send_json(forge.doctor())
            elif path.startswith("/api/status/"):
                job = forge.get(path.rsplit("/", 1)[-1])
                if not job:
                    self._send_json({"error": "no such job"}, 404)
                else:
                    self._send_json(job.snapshot())
            elif path.startswith("/download/"):
                self._serve_download(path.rsplit("/", 1)[-1])
            else:
                self._send_json({"error": "not found"}, 404)

        def do_POST(self):
            path = self.path.split("?", 1)[0]
            if path == "/api/build":
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length) if length else b"{}"
                try:
                    data = json.loads(raw or b"{}")
                    idea = str(data.get("idea", "")).strip()
                except Exception:
                    self._send_json({"error": "invalid JSON"}, 400)
                    return
                if not idea:
                    self._send_json({"error": "idea is required"}, 400)
                    return
                job = forge.start_build(idea)
                self._send_json({"id": job.id})
            else:
                self._send_json({"error": "not found"}, 404)

        def _serve_index(self):
            index = WEB_DIR / "index.html"
            if not index.exists():
                self._send_json({"error": "index.html missing"}, 500)
                return
            self._send_bytes(index.read_bytes(), "text/html; charset=utf-8")

        def _serve_download(self, job_id: str):
            job = forge.get(job_id)
            if not job or not job.apk_path:
                self._send_json({"error": "no APK for that job"}, 404)
                return
            apk = Path(job.apk_path)
            if not apk.exists():
                self._send_json({"error": "APK file is gone"}, 410)
                return
            # application/vnd.android.package-archive makes Android offer to install.
            self._send_bytes(
                apk.read_bytes(),
                "application/vnd.android.package-archive",
                filename=apk.name,
            )

    return Handler


def serve(config: Config, host: str, port: int) -> ThreadingHTTPServer:
    forge = Forge(config)
    httpd = ThreadingHTTPServer((host, port), make_handler(forge))
    return httpd
