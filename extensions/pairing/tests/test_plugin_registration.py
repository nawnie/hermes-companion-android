from hermes_mobile.device_store import DeviceStore
from hermes_mobile.plugin import register_all


class FakeCtx:
    def __init__(self):
        self.hooks = {}

    def register_dashboard_auth_provider(self, provider):
        pass

    def register_cli_command(self, *a, **k):
        pass

    def register_platform(self, **k):
        pass

    def register_hook(self, name, cb):
        self.hooks.setdefault(name, []).append(cb)


def test_register_all_does_not_register_unrequested_hooks(tmp_path):
    ctx = FakeCtx()
    register_all(ctx, store=DeviceStore(path=tmp_path / "devices.json"))
    assert ctx.hooks == {}
