"""
mitmproxy addon that stands in for a Databricks workspace so you can watch the
JDBC driver's OAuth M2M token request without touching a real tenant.

    mitmweb --listen-port 8888 -s fake_workspace.py --set connection_strategy=lazy

Nothing leaves your machine: every request is answered here. The token endpoint
replies the way our real workspace did: 403 when the driver asks for all-apis,
200 when it asks for a scope the service principal actually has.
"""

import base64
import json
import time
import uuid
from urllib.parse import parse_qs

from mitmproxy import http

ASSIGNED_SCOPES = {"sql"}


def _json(flow: http.HTTPFlow, status: int, body: dict) -> None:
    flow.response = http.Response.make(
        status, json.dumps(body).encode(), {"Content-Type": "application/json"}
    )


def _client_id(flow: http.HTTPFlow) -> str:
    auth = flow.request.headers.get("Authorization", "")
    if auth.startswith("Basic "):
        return base64.b64decode(auth[6:]).decode().split(":", 1)[0]
    return parse_qs(flow.request.get_text()).get("client_id", ["unknown"])[0]


def _fake_jwt(scope: str) -> str:
    def part(obj: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(obj).encode()).rstrip(b"=").decode()

    now = int(time.time())
    return ".".join([
        part({"alg": "HS256", "typ": "JWT"}),
        part({"sub": "fake-service-principal", "scope": scope, "iat": now, "exp": now + 3600}),
        "fake-signature",
    ])


def request(flow: http.HTTPFlow) -> None:
    base = f"https://{flow.request.pretty_host}"
    path = flow.request.path.split("?", 1)[0]

    if path == "/oidc/.well-known/oauth-authorization-server":
        _json(flow, 200, {
            "issuer": f"{base}/oidc",
            "authorization_endpoint": f"{base}/oidc/v1/authorize",
            "token_endpoint": f"{base}/oidc/v1/token",
        })
        return

    if path == "/oidc/v1/token" and flow.request.method == "POST":
        form = parse_qs(flow.request.get_text())
        requested = form.get("scope", [""])[0].split()
        missing = [s for s in requested if s not in ASSIGNED_SCOPES]
        if missing:
            _json(flow, 403, {
                "error": "access_denied",
                "request_id": str(uuid.uuid4()),
                "error_description": (
                    f"Scopes '{' '.join(missing)}' are not assigned to the client "
                    f"{_client_id(flow)}"
                ),
            })
        else:
            _json(flow, 200, {
                "access_token": _fake_jwt(" ".join(requested)),
                "token_type": "Bearer",
                "scope": " ".join(requested),
                "expires_in": 3600,
            })
        return

    _json(flow, 404, {"error_code": "NOT_FOUND", "message": f"fake workspace has no {path}"})
