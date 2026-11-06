from fastapi import FastAPI, Request
from fastapi.testclient import TestClient

from hermes_cli.dashboard_auth import clear_providers, register_provider
from hermes_cli.dashboard_auth.middleware import gated_auth_middleware
from hermes_mobile.auth_provider import MobileDeviceProvider
from hermes_mobile.device_store import DeviceStore


def test_real_hermes_gate_redeems_and_refreshes_pairing(tmp_path, monkeypatch):
    from hermes_cli import webapp_sign_in
    # This fixture is a standalone dashboard, not the Desktop Webapp surface.
    monkeypatch.setattr(webapp_sign_in, 'renew', lambda response, request: None)
    clock = [1000000]
    store = DeviceStore(tmp_path / 'devices.json', clock=lambda: clock[0])
    _, token = store.create_device('phone', one_shot=True)
    app = FastAPI()
    app.state.auth_required = True
    app.middleware('http')(gated_auth_middleware)

    @app.get('/api/auth/me')
    def me(request: Request):
        return {'provider': request.state.session.provider}

    clear_providers()
    register_provider(MobileDeviceProvider(store=store))
    try:
        client = TestClient(app, base_url='https://test.example')
        client.cookies.set('hermes_session_rt', token, domain='test.example', path='/')
        first = client.get('/api/auth/me')
        assert first.status_code == 200
        assert first.json()['provider'] == 'mobile-device'
        rotated = client.cookies.get('__Host-hermes_session_rt')
        assert rotated and rotated != token
        client.cookies.delete('__Host-hermes_session_at')
        second = client.get('/api/auth/me')
        assert second.status_code == 200
        assert second.json()['provider'] == 'mobile-device'
        assert client.cookies.get('__Host-hermes_session_rt') != rotated
    finally:
        clear_providers()
