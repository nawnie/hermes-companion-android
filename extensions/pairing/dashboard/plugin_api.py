"""Dashboard API shim — imported by hermes' plugin-API mounter.

The dashboard web server imports this file (manifest.json ``api`` field,
which must point inside ``dashboard/``) as a standalone module named
``hermes_dashboard_plugin_mobile`` and reads its module-level ``router``
(CONTRACTS.md §2.2). The real routes live in ``hermes_mobile.plugin_api``
so they are import-testable as a normal package; this shim only makes
the plugin root importable and re-exports the router.
"""

from __future__ import annotations

import sys
from pathlib import Path

_PLUGIN_ROOT = Path(__file__).resolve().parent.parent
if str(_PLUGIN_ROOT) not in sys.path:
    sys.path.insert(0, str(_PLUGIN_ROOT))

from fastapi import APIRouter  # noqa: E402
from hermes_mobile.plugin_api import router as upstream_router  # noqa: E402

# This fork is an authentication and pairing extension. Do not expose the
# upstream plugin's unrelated mailbox, push or memory editing endpoints.
router = APIRouter()
_PAIR_ROUTES = {
    '/pair/address', '/pair/start', '/pair/cancel/{device_id}', '/pair/status/{device_id}'
}
for route in upstream_router.routes:
    if route.path in _PAIR_ROUTES:
        router.routes.append(route)
