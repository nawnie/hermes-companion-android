from pathlib import Path
import importlib.util
import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("companion_installer", ROOT / "install_companion_plugins.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def test_install_both_plugins_and_repeat(tmp_path):
    phone = tmp_path / "phone-source"
    (phone / "herald_push").mkdir(parents=True)
    (phone / "herald_push" / "phone.py").write_text("phone_screen_control", encoding="utf-8")
    (phone / "__init__.py").write_text("", encoding="utf-8")
    (phone / "plugin.yaml").write_text("name: herald-push\nprovides_tools: [phone_screen_control]", encoding="utf-8")
    home = tmp_path / "home"
    installer.install(home, phone)
    assert (home / "plugins" / "herald-push" / "herald_push" / "phone.py").read_text() == "phone_screen_control"
    assert (home / "plugins" / "hermes-companion-pairing" / "dashboard" / "plugin_api.py").is_file()
    assert (home / "plugins" / "hermes-companion-pairing" / "desktop" / "plugin.js").is_file()
    assert all(x.startswith("already present:") for x in installer.install(home, phone))
    assert not (home / "config.yaml").exists()


def test_existing_different_plugin_is_preserved_without_partial_install(tmp_path):
    phone = tmp_path / "phone-source"
    (phone / "herald_push").mkdir(parents=True)
    (phone / "__init__.py").write_text("", encoding="utf-8")
    (phone / "plugin.yaml").write_text("name: herald-push\nprovides_tools: [phone_screen_control]", encoding="utf-8")
    existing = tmp_path / "home" / "plugins" / "herald-push"
    existing.mkdir(parents=True)
    (existing / "plugin.yaml").write_text("other", encoding="utf-8")
    with pytest.raises(FileExistsError):
        installer.install(tmp_path / "home", phone)
    assert (existing / "plugin.yaml").read_text() == "other"
    assert not (tmp_path / "home" / "plugins" / "hermes-companion-pairing").exists()


def test_real_hermes_loader_discovers_installed_plugins(tmp_path, monkeypatch):
    import os
    import subprocess
    import sys
    import json
    runtime = os.environ.get("HERMES_TEST_PYTHON")
    core = os.environ.get("HERMES_TEST_CORE")
    phone = os.environ.get("HERMES_TEST_PHONE_TOOLS")
    if not all((runtime, core, phone)):
        pytest.skip("Set HERMES_TEST_PYTHON, HERMES_TEST_CORE and HERMES_TEST_PHONE_TOOLS for real-loader integration")
    home = tmp_path / "home"
    installer.install(home, Path(phone))
    (home / "config.yaml").write_text("plugins:\n  enabled:\n    - hermes-companion-pairing\n    - herald-push\n", encoding="utf-8")
    env = os.environ.copy()
    env["HERMES_HOME"] = str(home)
    env["PYTHONPATH"] = core
    code = '''from hermes_cli.plugins import discover_plugins, get_plugin_manager
from hermes_cli.dashboard_auth import list_providers
from tools.registry import registry
import json
discover_plugins()
m = get_plugin_manager()
for name in ("hermes-companion-pairing", "herald-push"):
    p = m._plugins[name]
    assert p.enabled and not p.error, (name, p.error)
assert "mobile-device" in [p.name for p in list_providers()]
assert "phone_screen_control" in registry.get_tool_names_for_toolset("herald_phone")
print("REAL_PLUGIN_REGISTRATION_OK")
'''
    result = subprocess.run([runtime, "-c", code], cwd=tmp_path, env=env, capture_output=True, text=True, timeout=45)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "REAL_PLUGIN_REGISTRATION_OK" in result.stdout


def test_existing_matching_pairing_link_is_reused(tmp_path):
    import os
    phone = tmp_path / "phone-source"
    (phone / "herald_push").mkdir(parents=True)
    (phone / "__init__.py").write_text("", encoding="utf-8")
    (phone / "plugin.yaml").write_text("provides_tools: [phone_screen_control]", encoding="utf-8")
    plugins = tmp_path / "home" / "plugins"
    plugins.mkdir(parents=True)
    try:
        os.symlink(str(ROOT), str(plugins / "hermes-companion-pairing"), target_is_directory=True)
    except OSError:
        pytest.skip("Directory symlink creation unavailable")
    result = installer.install(tmp_path / "home", phone)
    assert result[0].startswith("already present:")
    assert (plugins / "hermes-companion-pairing").resolve() == ROOT.resolve()
