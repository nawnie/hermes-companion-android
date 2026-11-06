"""Dashboard API routes for the mobile app — /api/plugins/mobile/...

Exposed as a module-level ``router`` (FastAPI APIRouter) and mounted by
the dashboard's plugin system via ``dashboard/manifest.json``'s ``api``
field (CONTRACTS.md §2.2) — the actual imported file is the thin shim
``dashboard/plugin_api.py``, which re-exports this router.

Every route requires a *mobile-device* session: the gated auth
middleware attaches the verified Session to ``request.state.session``;
we derive the device id from ``user_id == "mobile:<device_id>"`` and
reject anything else (other providers' sessions, or loopback mode where
no session exists) with 403. A browser logged in via GitHub OAuth has
no business draining a phone's mailbox.

Routes:
* ``POST /push-token {"token": ...}`` — register/refresh the calling
  device's Expo push token.
* ``GET /mailbox`` — return and drain the device's queued messages.
* ``GET /me`` — device self-info (no token hashes ever leave the store).

Memory routes (``/memory/...``) are the exception to the device-only
rule: they accept ANY authenticated dashboard session. The host already
gates every ``/api/plugins/...`` request (legacy session-token
middleware in loopback mode, ``gated_auth_middleware`` otherwise — see
CONTRACTS.md §2.2), so by the time a handler runs the caller is an
authenticated dashboard user. We deliberately do NOT additionally
require the ``mobile-device`` provider: editing MEMORY.md/USER.md from
a browser session is the whole point.

* ``GET /memory/files`` — list the editable memory files.
* ``GET /memory/files/{name}`` — read one file (allowlisted name only).
* ``PUT /memory/files/{name} {"content": ...}`` — atomic full-file
  replace, ≤ 256 KiB. ``{name}`` is never path-joined; it is looked up
  in a fixed allowlist (``MEMORY.md``, ``USER.md``).
"""

from __future__ import annotations

import logging
import base64
import hmac
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import urlsplit

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from fastapi.responses import JSONResponse

from .auth_provider import PROVIDER_NAME
from .device_store import DeviceStore
from .mailbox import default_mailbox_dir, drain_messages, is_safe_device_id

logger = logging.getLogger(__name__)

router = APIRouter()


def _require_local_desktop(request: Request, secret: str) -> None:
    """Pairing is only available over the authenticated local desktop gateway."""
    host = (request.client.host if request.client else "").lower()
    forwarded = any(name in request.headers for name in ("forwarded", "x-forwarded-for", "x-real-ip"))
    session = getattr(request.state, "session", None)
    if host not in ("127.0.0.1", "::1") or forwarded or getattr(session, "provider", "") == PROVIDER_NAME:
        raise HTTPException(status_code=403, detail="Pairing requires the local desktop")
    # The Desktop SDK's own private data folder provides a capability that a
    # request proxied to loopback cannot obtain merely by forging headers.
    if os.name != 'nt' or not os.environ.get('APPDATA'):
        raise HTTPException(status_code=403, detail="Desktop pairing is unavailable on this host")
    path = Path(os.environ['APPDATA']) / 'Hermes' / 'plugin-files' / 'data' / 'hermes-companion-pairing' / 'pair-capability.txt'
    try:
        with path.open('r', encoding='utf-8') as file:
            expected = file.read(65)
    except (OSError, UnicodeError):
        raise HTTPException(status_code=403, detail="Open Connect phone in the local desktop") from None
    if not re.fullmatch(r'[0-9a-f]{64}', secret) or not re.fullmatch(r'[0-9a-f]{64}', expected) or not hmac.compare_digest(expected, secret):
        raise HTTPException(status_code=403, detail="Desktop pairing authorization failed")


class PairRequest(BaseModel):
    url: str
    secret: str


class DesktopRequest(BaseModel):
    secret: str


@router.post("/pair/address")
def pairing_address(request: Request, body: DesktopRequest):
    _require_local_desktop(request, body.secret)
    # The Desktop's backend can use a different port from the gateway Webapp.
    # Prefer the gateway's declared public address when present.
    try:
        from hermes_cli.config import load_config
        configured = str((load_config().get('webapp') or {}).get('public_url') or '').strip().rstrip('/')
        parts = urlsplit(configured)
        if parts.scheme == 'https' and parts.hostname and not (parts.username or parts.password or parts.query or parts.fragment):
            return {"url": configured}
    except Exception:
        pass
    port = request.url.port or 80
    try:
        raw = subprocess.run(["tailscale", "serve", "status", "--json"], capture_output=True, text=True,
                             timeout=3, check=True, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        web = json.loads(raw.stdout).get("Web", {})
        matches = []
        for name, record in web.items():
            handler = record.get("Handlers", {}).get("/", {})
            proxy = urlsplit(handler.get("Proxy", ""))
            if proxy.hostname in ("127.0.0.1", "localhost", "::1") and proxy.port == port:
                matches.append("https://" + name.removesuffix(":443"))
        return {"url": matches[0] if len(matches) == 1 else None}
    except (OSError, ValueError, subprocess.SubprocessError):
        return {"url": None}


@router.post("/pair/start")
def start_pairing(request: Request, body: PairRequest):
    _require_local_desktop(request, body.secret)
    url = body.url.strip().rstrip("/")
    parts = urlsplit(url)
    if parts.scheme != "https" or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
        raise HTTPException(status_code=400, detail="A reachable HTTPS dashboard address is required")
    import qrcode
    store = _get_store()
    device_id, token = store.create_device("Android phone", one_shot=True)
    try:
        payload = json.dumps({"url": url, "rt": token, "device_id": device_id}, separators=(",", ":"))
        code = qrcode.QRCode(border=4)
        code.add_data(payload)
        code.make(fit=True)
        matrix = code.get_matrix()
        cells = ''.join(f'<rect x="{x}" y="{y}" width="1" height="1"/>'
                        for y, row in enumerate(matrix) for x, value in enumerate(row) if value)
        svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {len(matrix)} {len(matrix)}"><rect width="100%" height="100%" fill="white"/><g fill="black">{cells}</g></svg>'
        image = base64.b64encode(svg.encode()).decode()
    except Exception:
        store.revoke(device_id)
        raise
    return JSONResponse({"device_id": device_id, "image": image, "expires_in": 120}, headers={"Cache-Control": "no-store"})


@router.post("/pair/cancel/{device_id}")
def cancel_pairing(device_id: str, request: Request, body: DesktopRequest):
    _require_local_desktop(request, body.secret)
    store = _get_store()
    store.revoke_if_pending(device_id)
    return {"ok": True}


@router.post("/pair/status/{device_id}")
def pairing_status(device_id: str, request: Request, body: DesktopRequest):
    _require_local_desktop(request, body.secret)
    device = _get_store().get_device(device_id)
    if not device or device.get("revoked"):
        return {"state": "expired"}
    return {"state": "pending" if device.get("pairing_pending") else "used"}

# Module-level singletons, injectable for tests via configure().
_store: Optional[DeviceStore] = None
_mailbox_dir: Optional[Path] = None
_memories_dir: Optional[Path] = None


def configure(
    store: Optional[DeviceStore] = None,
    mailbox_dir: Optional[Path] = None,
    memories_dir: Optional[Path] = None,
) -> None:
    """Inject store/mailbox/memories locations (tests); None resets to defaults."""
    global _store, _mailbox_dir, _memories_dir
    _store = store
    _mailbox_dir = Path(mailbox_dir) if mailbox_dir is not None else None
    _memories_dir = Path(memories_dir) if memories_dir is not None else None


def _get_store() -> DeviceStore:
    global _store
    if _store is None:
        _store = DeviceStore()
    return _store


def _get_mailbox_dir() -> Path:
    return _mailbox_dir if _mailbox_dir is not None else default_mailbox_dir()


def default_memories_dir() -> Path:
    """``<hermes home>/memories`` — where hermes' built-in store keeps
    MEMORY.md / USER.md (hermes_cli/web_server.py /api/memory uses
    ``get_hermes_home() / "memories"``).

    Uses hermes' canonical home resolution when available; falls back to
    ``$HERMES_HOME`` / ``~/.hermes`` so this module stays importable
    without hermes on the path (mirrors device_store.default_devices_path).
    """
    try:
        from hermes_constants import get_hermes_home  # type: ignore

        home = Path(get_hermes_home())
    except Exception:
        env = os.environ.get("HERMES_HOME", "").strip()
        home = Path(env) if env else Path.home() / ".hermes"
    return home / "memories"


def _get_memories_dir() -> Path:
    return _memories_dir if _memories_dir is not None else default_memories_dir()


def _require_device_id(request: Request) -> str:
    """Device id from the verified mobile-device session, else 403."""
    session = getattr(request.state, "session", None)
    if session is None:
        # Loopback/--insecure mode has no per-device identity — there is
        # no "calling device" to act on, so these routes are unusable.
        raise HTTPException(status_code=403, detail="mobile device session required")
    provider = getattr(session, "provider", "")
    user_id = getattr(session, "user_id", "") or ""
    if provider != PROVIDER_NAME or not user_id.startswith("mobile:"):
        raise HTTPException(status_code=403, detail="mobile device session required")
    device_id = user_id.split(":", 1)[1]
    if not is_safe_device_id(device_id):
        raise HTTPException(status_code=403, detail="mobile device session required")
    return device_id


class PushTokenBody(BaseModel):
    token: str


@router.post("/push-token")
def set_push_token(body: PushTokenBody, request: Request) -> Dict[str, Any]:
    """Register/refresh the calling device's Expo push token."""
    device_id = _require_device_id(request)
    token = body.token.strip()
    if not token:
        raise HTTPException(status_code=400, detail="token must be non-empty")
    try:
        ok = _get_store().set_push_token(device_id, token)
    except Exception as exc:
        logger.warning("hermes-mobile: push-token store write failed: %s", exc)
        raise HTTPException(status_code=503, detail="device store unavailable")
    if not ok:
        raise HTTPException(status_code=404, detail="device unknown or revoked")
    return {"ok": True}


class SessionClaimBody(BaseModel):
    session_id: str
    session_key: str = ""


@router.post("/session-claim")
def claim_session(body: SessionClaimBody, request: Request) -> Dict[str, Any]:
    """Bind the calling device to a session so session-stop hooks can target it."""
    device_id = _require_device_id(request)
    from .session_notify import get_registry

    sid = body.session_id.strip()
    skey = body.session_key.strip()
    # session_key is the persistent/stored id the app routes on; prefer it as
    # the canonical route id, falling back to session_id when absent.
    get_registry().claim(device_id, sid, skey, route_id=(skey or sid))
    return {"ok": True}


@router.get("/mailbox")
def get_mailbox(request: Request) -> Dict[str, List[Dict[str, Any]]]:
    """Return and drain the calling device's queued messages."""
    device_id = _require_device_id(request)
    try:
        messages = drain_messages(_get_mailbox_dir(), device_id)
    except Exception as exc:
        logger.warning("hermes-mobile: mailbox drain failed: %s", exc)
        raise HTTPException(status_code=503, detail="mailbox unavailable")
    return {"messages": messages}


@router.get("/me")
def me(request: Request) -> Dict[str, Any]:
    """Self-info for the calling device. Token hashes never leave the store."""
    device_id = _require_device_id(request)
    try:
        record = _get_store().get_device(device_id)
    except Exception as exc:
        logger.warning("hermes-mobile: device lookup failed: %s", exc)
        raise HTTPException(status_code=503, detail="device store unavailable")
    if record is None:
        raise HTTPException(status_code=404, detail="device unknown")
    return {
        "device_id": record.get("device_id", device_id),
        "name": record.get("name", ""),
        "created_at": record.get("created_at", 0),
        "last_refresh_at": record.get("last_refresh_at", 0),
        "revoked": bool(record.get("revoked", False)),
        "has_push_token": bool(record.get("push_token")),
    }


# ---------------------------------------------------------------------------
# Memory file CRUD — MEMORY.md / USER.md under <hermes home>/memories.
#
# Open to ANY authenticated dashboard session (the host's auth middleware
# is the gate — see module docstring); no _require_device_id here.
#
# SECURITY: ``{name}`` from the URL is NEVER path-joined. It is used only
# as a dict key into _MEMORY_FILE_ALLOWLIST; the filesystem path comes
# from the fixed allowlist value. Anything else → 404.
# ---------------------------------------------------------------------------

#: name → fixed filename. Values, not user input, become path components.
_MEMORY_FILE_ALLOWLIST: Dict[str, str] = {
    "MEMORY.md": "MEMORY.md",
    "USER.md": "USER.md",
}

#: PUT body size cap for the UTF-8 encoded content (256 KiB).
MEMORY_FILE_MAX_BYTES = 256 * 1024


def _memory_file_path(name: str) -> Path:
    """Resolve an allowlisted name to its path, else 404. Never joins *name*."""
    fixed = _MEMORY_FILE_ALLOWLIST.get(name)
    if fixed is None:
        raise HTTPException(
            status_code=404,
            detail="unknown memory file (allowed: MEMORY.md, USER.md)",
        )
    return _get_memories_dir() / fixed


class MemoryFileBody(BaseModel):
    content: str


@router.get("/memory/files")
def list_memory_files() -> Dict[str, List[Dict[str, Any]]]:
    """List the editable memory files (always both allowlisted names)."""
    files: List[Dict[str, Any]] = []
    base = _get_memories_dir()
    for fixed in _MEMORY_FILE_ALLOWLIST.values():
        path = base / fixed
        try:
            st = path.stat()
            files.append(
                {
                    "name": fixed,
                    "size": st.st_size,
                    "mtime": st.st_mtime,
                    "exists": True,
                }
            )
        except FileNotFoundError:
            files.append({"name": fixed, "size": 0, "mtime": 0, "exists": False})
        except OSError as exc:
            logger.warning("hermes-mobile: memory file stat failed: %s", exc)
            raise HTTPException(status_code=503, detail="memory store unavailable")
    return {"files": files}


@router.get("/memory/files/{name}")
def read_memory_file(name: str) -> Dict[str, str]:
    """Read one memory file. Missing-but-allowlisted → empty content."""
    path = _memory_file_path(name)
    try:
        content = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        content = ""
    except OSError as exc:
        logger.warning("hermes-mobile: memory file read failed: %s", exc)
        raise HTTPException(status_code=503, detail="memory store unavailable")
    return {"name": _MEMORY_FILE_ALLOWLIST[name], "content": content}


@router.put("/memory/files/{name}")
def write_memory_file(name: str, body: MemoryFileBody) -> Dict[str, Any]:
    """Atomically replace one memory file (tmp file + os.replace, 0600)."""
    path = _memory_file_path(name)
    data = body.content.encode("utf-8")
    if len(data) > MEMORY_FILE_MAX_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"content exceeds {MEMORY_FILE_MAX_BYTES} bytes",
        )
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(
            prefix=f".{path.name}.", suffix=".tmp", dir=str(path.parent)
        )
        try:
            # mkstemp already creates the file 0600.
            with os.fdopen(fd, "wb") as fh:
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp_name, path)
        except BaseException:
            try:
                os.unlink(tmp_name)
            except OSError:
                pass
            raise
    except HTTPException:
        raise
    except OSError as exc:
        logger.warning("hermes-mobile: memory file write failed: %s", exc)
        raise HTTPException(status_code=503, detail="memory store unavailable")
    return {"ok": True, "name": _MEMORY_FILE_ALLOWLIST[name], "size": len(data)}
