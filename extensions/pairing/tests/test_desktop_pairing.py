import json
from types import SimpleNamespace

from fastapi import FastAPI
from fastapi.testclient import TestClient

from hermes_mobile import plugin_api
from hermes_mobile.device_store import DeviceStore


def test_dashboard_shim_only_mounts_pairing_routes():
    from dashboard.plugin_api import router
    assert {route.path for route in router.routes} == {
        '/pair/address', '/pair/start', '/pair/cancel/{device_id}', '/pair/status/{device_id}'
    }


def app_for(store, provider="basic"):
    plugin_api.configure(store=store)
    app = FastAPI()

    @app.middleware('http')
    async def session(request, call_next):
        request.state.session = SimpleNamespace(provider=provider)
        return await call_next(request)

    app.include_router(plugin_api.router)
    return app


def test_pairing_restricted_and_cancelled(tmp_path, monkeypatch):
    monkeypatch.setenv('APPDATA', str(tmp_path))
    folder = tmp_path / 'Hermes' / 'plugin-files' / 'data' / 'hermes-companion-pairing'
    folder.mkdir(parents=True)
    secret = 'a' * 64
    (folder / 'pair-capability.txt').write_text(secret)
    store = DeviceStore(tmp_path / 'devices.json')
    app = app_for(store)
    local = TestClient(app, client=('127.0.0.1', 1234))
    remote = TestClient(app, client=('192.0.2.1', 1234))
    assert remote.post('/pair/start', json={'url': 'https://example.com', 'secret': secret}).status_code == 403
    assert local.post('/pair/start', json={'url': 'https://example.com', 'secret': secret}, headers={'X-Forwarded-For': '192.0.2.1'}).status_code == 403
    assert local.post('/pair/start', json={'url': 'https://example.com', 'secret': 'b' * 64}).status_code == 403
    assert local.post('/pair/start', json={'url': 'http://example.com', 'secret': secret}).status_code == 400
    response = local.post('/pair/start', json={'url': 'https://example.com', 'secret': secret})
    assert response.status_code == 200
    result = response.json()
    assert response.headers['cache-control'] == 'no-store'
    assert 'rt' not in result and result['image']
    assert store.get_device(result['device_id'])['pairing_pending']
    assert local.post('/pair/status/' + result['device_id'], json={'secret': secret}).json()['state'] == 'pending'
    assert local.post('/pair/cancel/' + result['device_id'], json={'secret': secret}).status_code == 200
    assert store.get_device(result['device_id'])['revoked']
    assert local.post('/pair/status/' + result['device_id'], json={'secret': secret}).json()['state'] == 'expired'


def test_mobile_session_cannot_create_pairings(tmp_path):
    app = app_for(DeviceStore(tmp_path / 'devices.json'), provider='mobile-device')
    local = TestClient(app, client=('127.0.0.1', 1234))
    assert local.post('/pair/start', json={'url': 'https://example.com', 'secret': 'a' * 64}).status_code == 403


def test_cancel_does_not_revoke_redeemed_device(tmp_path):
    store = DeviceStore(tmp_path / 'devices.json')
    device, token = store.create_device('phone', one_shot=True)
    store.rotate_refresh(token)
    assert not store.revoke_if_pending(device)
    assert not store.get_device(device)['revoked']
