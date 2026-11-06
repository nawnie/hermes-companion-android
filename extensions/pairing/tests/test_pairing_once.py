import pytest
import subprocess
import sys

from hermes_mobile.device_store import DeviceStore, ExpiredRefreshTokenError, UnknownRefreshTokenError


def test_pairing_code_expires(tmp_path):
    now = [1000]
    store = DeviceStore(tmp_path / 'devices.json', clock=lambda: now[0])
    _, token = store.create_device('phone', one_shot=True)
    now[0] += 120
    with pytest.raises(ExpiredRefreshTokenError):
        store.rotate_refresh(token)


def test_pairing_code_can_only_be_redeemed_once(tmp_path):
    store = DeviceStore(tmp_path / 'devices.json')
    device, token = store.create_device('phone', one_shot=True)
    access, refresh, _ = store.rotate_refresh(token)
    assert store.verify_access(access)['device_id'] == device
    with pytest.raises(UnknownRefreshTokenError):
        store.rotate_refresh(token)
    store.rotate_refresh(refresh)


def test_store_locks_across_processes(tmp_path):
    store = DeviceStore(tmp_path / 'devices.json', lock_timeout=0.25)
    child = (
        'from pathlib import Path; from hermes_mobile.device_store import DeviceStore; '
        'import sys; DeviceStore(Path(sys.argv[1]), lock_timeout=.25).create_device("other")'
    )
    with store._locked():
        result = subprocess.run([sys.executable, '-c', child, str(tmp_path / 'devices.json')],
                                capture_output=True, text=True, timeout=5)
    assert result.returncode != 0
    assert 'timed out' in result.stderr
    assert store.create_device('after')
