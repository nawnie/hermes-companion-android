"""Local pairing window for the Hermes mobile plugin. Never logs or saves the QR."""
from __future__ import annotations

import json
import time
import tkinter as tk
from tkinter import messagebox, ttk
from urllib.parse import urlsplit

import qrcode
from PIL import ImageTk

from hermes_mobile.device_store import DeviceStore, PAIRING_TTL_SECONDS


class PairWindow:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.store = DeviceStore()
        self.device_id = None
        self.expiry = 0.0
        self.image = None
        root.title("Connect Hermes on your phone")
        root.resizable(False, False)
        body = ttk.Frame(root, padding=24)
        body.grid()
        ttk.Label(body, text="Connect your phone", font=("Segoe UI", 19, "bold")).grid(sticky="w")
        ttk.Label(body, text="Enter your secure Hermes dashboard address, then scan the code in the phone app.", wraplength=380).grid(sticky="w", pady=(8, 14))
        self.url = tk.StringVar()
        ttk.Entry(body, textvariable=self.url, width=48).grid(sticky="ew")
        ttk.Button(body, text="Create pairing code", command=self.create).grid(sticky="ew", pady=12)
        self.qr = ttk.Label(body)
        self.qr.grid(pady=8)
        self.status = ttk.Label(body, text="Pairing codes expire after two minutes.", wraplength=380)
        self.status.grid(sticky="w")
        root.protocol("WM_DELETE_WINDOW", self.close)

    def revoke_pending(self) -> None:
        if self.device_id:
            self.store.revoke_if_pending(self.device_id)
        self.device_id = None
        self.qr.configure(image="")
        self.image = None

    def create(self) -> None:
        url = self.url.get().strip().rstrip("/")
        parsed = urlsplit(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
            messagebox.showerror("Secure address needed", "Enter an HTTPS dashboard address reachable from your phone.")
            return
        try:
            self.revoke_pending()
            device_id, token = self.store.create_device("Android phone", one_shot=True)
            self.device_id = device_id
            self.expiry = time.monotonic() + PAIRING_TTL_SECONDS
            payload = json.dumps({"url": url, "rt": token, "device_id": device_id}, separators=(",", ":"))
            self.image = ImageTk.PhotoImage(qrcode.make(payload).resize((300, 300)))
            self.qr.configure(image=self.image)
            self.tick(device_id)
        except Exception as exc:
            self.revoke_pending()
            messagebox.showerror("Pairing unavailable", str(exc))

    def tick(self, device_id: str) -> None:
        if self.device_id != device_id:
            return
        device = self.store.get_device(device_id)
        if device and not device.get("pairing_pending") and not device.get("revoked"):
            self.device_id = None
            self.qr.configure(image="")
            self.image = None
            self.status.configure(text="Code used. Check your phone; if it did not connect, create a new code.")
        elif time.monotonic() >= self.expiry:
            self.revoke_pending()
            self.status.configure(text="Code expired. Create another to connect.")
        else:
            self.status.configure(text=f"Scan this code in the app. Expires in {int(self.expiry - time.monotonic()) + 1} seconds.")
            self.root.after(1000, lambda: self.tick(device_id))

    def close(self) -> None:
        self.revoke_pending()
        self.root.destroy()


if __name__ == "__main__":
    window = tk.Tk()
    PairWindow(window)
    window.mainloop()
