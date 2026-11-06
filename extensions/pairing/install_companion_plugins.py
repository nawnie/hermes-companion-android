"""Stage the Companion pairing and custom phone-tools plugins into a chosen Hermes home.

This only copies the plugins. Enable both with Hermes' plugin CLI before starting
that home. It never runs Hermes or changes an existing plugin installation.
"""
from __future__ import annotations

import argparse
import filecmp
from pathlib import Path
import shutil
import sys

PAIRING = Path(__file__).resolve().parent
DEFAULT_PHONE_TOOLS = PAIRING.parent / "Hermes Companion Phone Tools"


def _files(root: Path):
    return {p.relative_to(root): p for p in root.rglob("*") if p.is_file()
            and "__pycache__" not in p.parts and p.suffix != ".pyc"}


def install(home: Path, phone_tools: Path) -> list[str]:
    sources = (("hermes-companion-pairing", PAIRING, ("__init__.py", "plugin.yaml", "hermes_mobile", "dashboard", "desktop")),
               ("herald-push", phone_tools.resolve(), ("__init__.py", "plugin.yaml", "herald_push")))
    for name, root, items in sources:
        for item in items:
            if not (root / item).exists():
                raise ValueError(f"{name} source missing {root / item}")
    if "phone_screen_control" not in (phone_tools / "plugin.yaml").read_text(encoding="utf-8"):
        raise ValueError("Phone tools source does not declare phone_screen_control")
    plugins = home.resolve() / "plugins"
    # Refuse partial replacement of any existing, different installation.
    for name, root, items in sources:
        dest = plugins / name
        if dest.exists():
            if dest.resolve() == root.resolve():
                continue
            if not dest.is_dir() or dest.is_symlink() or getattr(dest, "is_junction", lambda: False)():
                raise FileExistsError(f"Existing plugin is not a regular directory: {dest}")
            for item in items:
                src = root / item
                pairs = [(src, dest / item)] if src.is_file() else [(v, dest / item / k) for k, v in _files(src).items()]
                if any(not b.is_file() or not filecmp.cmp(a, b, shallow=False) for a, b in pairs):
                    raise FileExistsError(f"Existing plugin differs: {dest}")
    installed = []
    for name, root, items in sources:
        dest = plugins / name
        if dest.exists():
            installed.append(f"already present: {dest}")
            continue
        dest.mkdir(parents=True)
        for item in items:
            source = root / item
            if source.is_dir():
                shutil.copytree(source, dest / item, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
            else:
                shutil.copy2(source, dest / item)
        installed.append(f"installed: {dest}")
    return installed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--home", type=Path, required=True, help="Target Hermes home")
    parser.add_argument("--phone-tools", type=Path, default=DEFAULT_PHONE_TOOLS)
    args = parser.parse_args()
    try:
        for line in install(args.home, args.phone_tools):
            print(line)
    except (ValueError, OSError) as exc:
        print(f"Installation failed: {exc}", file=sys.stderr)
        return 1
    print("Enable both plugins for this HERMES_HOME using Hermes: ")
    print("  hermes plugins enable hermes-companion-pairing")
    print("  hermes plugins enable herald-push")
    print("The gateway runtime also needs qrcode>=7.4.2,<9 and cryptography.")
    print("Enable Connect phone in Hermes Desktop; an HTTPS address reachable by the phone is required.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
