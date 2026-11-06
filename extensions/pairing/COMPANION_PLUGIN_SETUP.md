# Hermes Companion plugin setup

The Companion needs both `hermes-companion-pairing` and the customized
`herald-push` in the adjacent `phone-tools` directory. The
upstream herald-push repository does not include `phone_screen_control`.

For the intended Hermes home, run from this directory (substitute the two
paths):

```powershell
python .\install_companion_plugins.py --home '<Hermes-home>' --phone-tools ..\phone-tools
$env:HERMES_HOME = '<Hermes-home>'
hermes plugins enable hermes-companion-pairing
hermes plugins enable herald-push
```

The installer copies the agent and Desktop halves of the pairing plugin and the
custom phone plugin. It verifies the phone plugin declares the screen tool,
leaves configuration unchanged, and reuses a pairing junction pointing to this
checkout. It leaves a different existing plugin intact. The `enable` commands
change the selected home's configuration; run them against the intended home.
The gateway's own Python environment needs `qrcode>=7.4.2,<9` and `cryptography`.
The installer does not install packages or start or restart Hermes.

The pairing package includes `desktop/plugin.js`. Hermes Desktop normally
reconciles unified packages to `<Hermes root>/desktop-plugins/`; enable the
**Connect phone** Desktop plugin after the Desktop refreshes its plugin list.
An older cached plugin with the same ID can prevent discovery and must be
resolved in the Desktop before relying on the page. No Desktop refresh was
verified by the installer. On Windows the page also needs the pairing plugin's
Desktop data directory for its capability file; see
`prepare-windows-capability.ps1` and inspect existing ownership before using it.

The Desktop page tries to discover an existing Tailscale HTTPS mapping for the
gateway port. Otherwise enter an already configured HTTPS dashboard address
reachable by the phone. This installer creates no HTTPS endpoint, proxy, DNS,
certificate, firewall rule, or public route; there is no usable default HTTPS
address until one has been configured. Android must trust the endpoint's
certificate. The gateway must have dashboard authentication engaged so its
mobile-device provider can issue sessions.

After the chosen gateway loads the plugins, `/herald-push info` should list
`phone_screen_control` in `phone_tools`. Enable screen control in Companion and
the Android accessibility service. A new chat may be needed for Hermes to see
a newly enabled tool.

Temporary-home tests exercised installation and the installed Hermes plugin
loader. They do not establish a running gateway, Desktop pairing page, HTTPS
connection, or Android round trip.
