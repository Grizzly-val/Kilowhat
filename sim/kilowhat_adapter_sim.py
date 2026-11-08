#!/usr/bin/env python3
"""
Kilowhat adapter-box simulator
==============================
Stands in for the ESP32 + PZEM-004T + RFID adapter so the Kotlin app can be
built and tested on a PC.

Inputs happen in a small GUI control panel (what you plug into the one outlet);
all logs stay in the terminal. The hardware runs continuously from the moment
the script starts (1 Hz loop), so the phone can pair at any time.

Control panel
  * Appliance ID (empty = "unknown_id"), seconds, Active / Standby, [Plug in].
  * While plugged: a countdown, and [Remove appliance] to unplug early.
  * Unplugging (timer or button) starts the 5 s cooldown:
      - plug the SAME id again  -> the session simply continues
      - plug a DIFFERENT id     -> the previous session is posted right away,
                                   then a new session starts for the new plug

Outlet states
  * Nothing plugged in : ~0 W, mains voltage ~230 V, ghost noise (gated to 0 W),
                         plus occasional STRAY CURRENT spikes (ignored by the
                         session logic because no RFID tag is present).
  * Plugged, standby   : tag present but the load stays under the 4 W gate.
  * Plugged, active    : normal load, session logic with 5 s cooldown.

Two simulated links:
  * "Bluetooth" (BLE)  -> local HTTP server, default port 8001
        POST /pair        phone selects this node (sets paired = True)
        GET  /ble-stream  live reading, polled every ~1s by the app
        POST /unpair      (extra, handy for testing) resets paired = False
  * "Wi-Fi"            -> one HTTP POST per finished session to the Kotlin
                          backend (default http://localhost:8080/api/sessions)

Terminal log prefix:  [HW ][# BLE]   where # is how many phones are paired.
Wi-Fi pushes use their own [WIFI] tag.

Only the standard library is used (tkinter ships with Python on Windows/macOS;
on some Linux distros install the python3-tk package).

Usage:
    python kilowhat_adapter_sim.py
    python kilowhat_adapter_sim.py --backend-url http://localhost:8080/api/sessions --port 8001
    python kilowhat_adapter_sim.py --stray-interval 15     # stray current about every 15 s
    python kilowhat_adapter_sim.py --stray-interval 0      # no stray current
"""

import argparse
import json
import math
import os
import queue
import random
import sys
import threading
import time
import urllib.error
import urllib.request
import zlib
from dataclasses import dataclass
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import tkinter as tk
    from tkinter import font as tkfont
    from tkinter import ttk
except ImportError:  # handled in main() with a clear message
    tk = None

# --------------------------------------------------------------------------- #
# Hardcoded identity and hardware parameters
# --------------------------------------------------------------------------- #
OUTLET_ID = "OUTLET_NODE_01"
NO_APPLIANCE = "NONE"        # what the BLE stream reports when nothing is plugged in
UNKNOWN_ID = "unknown_id"    # default id for an appliance with no id entered
MAX_ID_LEN = 50              # matches the database column

NOISE_GATE_W = 4.0        # readings below this are treated as 0 W
COOLDOWN_S = 5            # idle time required before a session is closed
MIN_SESSION_WH = 0.01     # sessions consuming less than this are discarded
NOMINAL_VOLTAGE = 230.0
PZEM_MIN_V = 80.0         # PZEM-004T measuring range
PZEM_MAX_V = 260.0
STANDBY_MAX_W = 3.6       # standby draw always stays under the noise gate

DEFAULT_PORT = 8001
DEFAULT_BACKEND_URL = "http://localhost:8080/api/sessions"
DEFAULT_STRAY_INTERVAL_S = 40.0   # average seconds between stray-current spikes

# --------------------------------------------------------------------------- #
# Terminal colors and thread-safe logging
# --------------------------------------------------------------------------- #
if os.name == "nt":
    os.system("")  # enables ANSI escape codes on Windows terminals

RESET = "\033[0m"
BOLD = "\033[1m"
DIM = "\033[2m"
RED = "\033[91m"
GREEN = "\033[92m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
MAGENTA = "\033[95m"
ORANGE = "\033[38;5;208m"

_print_lock = threading.Lock()
_telemetry_ref = None   # set in main(); lets the [HW ][# BLE] prefix show the paired count


def log(label: str, color: str, message: str) -> None:
    """Generic tagged line, used for [WIFI] and [SIM ]."""
    stamp = datetime.now().strftime("%H:%M:%S")
    with _print_lock:
        print(
            f"{DIM}{stamp}{RESET} {color}{BOLD}[{label:<4}]{RESET} {color}{message}{RESET}",
            flush=True,
        )


def hw(color: str, message: str) -> None:
    """Hardware/BLE line with the merged prefix: [HW ][# BLE]."""
    paired = _telemetry_ref.paired_count() if _telemetry_ref is not None else 0
    ble_color = CYAN if paired else DIM
    stamp = datetime.now().strftime("%H:%M:%S")
    with _print_lock:
        print(
            f"{DIM}{stamp}{RESET} {color}{BOLD}[HW ]{RESET}"
            f"{ble_color}{BOLD}[{paired} BLE]{RESET} {color}{message}{RESET}",
            flush=True,
        )


def iso_now() -> str:
    return datetime.now().astimezone().isoformat(timespec="milliseconds")


# --------------------------------------------------------------------------- #
# Shared telemetry state (written by the firmware thread, read by HTTP threads)
# --------------------------------------------------------------------------- #
class TelemetryState:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._paired = False
        self._reading = {
            "outlet_id": OUTLET_ID,
            "active_appliance_rfid": NO_APPLIANCE,
            "watts": 0.0,
            "voltage": 0.0,
            "current": 0.0,
        }

    def set_paired(self, value: bool) -> None:
        with self._lock:
            self._paired = value

    def is_paired(self) -> bool:
        with self._lock:
            return self._paired

    def paired_count(self) -> int:
        return 1 if self.is_paired() else 0

    def update(self, rfid: str, watts: float, voltage: float, current: float) -> None:
        with self._lock:
            self._reading = {
                "outlet_id": OUTLET_ID,
                "active_appliance_rfid": rfid,
                "watts": round(watts, 1),
                "voltage": round(voltage, 1),
                "current": round(current, 3),
            }

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self._reading)


# --------------------------------------------------------------------------- #
# Simulated "Bluetooth" link: local HTTP server
# --------------------------------------------------------------------------- #
def make_handler(telemetry: TelemetryState):
    class BleHandler(BaseHTTPRequestHandler):
        server_version = "KilowhatNode/1.0"

        def _send_json(self, status: int, body: dict) -> None:
            data = json.dumps(body).encode("utf-8")
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
            except (BrokenPipeError, ConnectionResetError):
                pass  # client hung up; nothing to do

        def _drain_body(self) -> None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > 0:
                self.rfile.read(length)

        def do_GET(self) -> None:
            path = self.path.split("?", 1)[0]
            if path == "/ble-stream":
                if not telemetry.is_paired():
                    hw(RED, "stream request rejected (not paired, POST /pair first)")
                    self._send_json(403, {
                        "error": "not_paired",
                        "message": "Phone is not paired with this node. POST /pair first.",
                    })
                    return
                self._send_json(200, telemetry.snapshot())
            else:
                self._send_json(404, {"error": "not_found"})

        def do_POST(self) -> None:
            self._drain_body()
            path = self.path.split("?", 1)[0]
            if path == "/pair":
                telemetry.set_paired(True)
                hw(MAGENTA, f"phone paired with {OUTLET_ID}")
                self._send_json(200, {"status": "paired", "outlet_id": OUTLET_ID})
            elif path == "/unpair":
                telemetry.set_paired(False)
                hw(MAGENTA, "phone disconnected")
                self._send_json(200, {"status": "unpaired", "outlet_id": OUTLET_ID})
            else:
                self._send_json(404, {"error": "not_found"})

        def log_message(self, format, *args) -> None:  # silence default access log
            pass

    return BleHandler


# --------------------------------------------------------------------------- #
# Virtual hardware models
# --------------------------------------------------------------------------- #
@dataclass(frozen=True)
class Stray:
    active: bool = False
    watts: float = 0.0


NO_STRAY = Stray()


class StrayCurrentModel:
    """Occasional stray current on an empty outlet: a brief 1-2 s power spike."""

    def __init__(self, mean_interval_s: float) -> None:
        self._mean = mean_interval_s
        self._extra_ticks = 0

    def step(self, allowed: bool) -> Stray:
        if not allowed:                       # something is plugged in: no stray current
            self._extra_ticks = 0
            return NO_STRAY
        if self._extra_ticks > 0:
            self._extra_ticks -= 1            # spike continues one more second
        elif self._mean > 0 and random.random() < 1.0 / self._mean:
            self._extra_ticks = random.choice([0, 0, 1])   # lasts 1 s (2/3) or 2 s (1/3)
        else:
            return NO_STRAY
        return Stray(True, random.uniform(2.0, 15.0))


class ApplianceProfile:
    """Deterministic fake load: the same id always draws a similar wattage."""

    def __init__(self, tag: str) -> None:
        seed = zlib.crc32(tag.encode("utf-8"))
        self.base_w = 60.0 + (seed % 541)                      # 60 .. 600 W when in use
        self.phase = ((seed >> 8) % 628) / 100.0
        self.standby_w = 0.6 + ((seed >> 16) % 25) / 10.0      # 0.6 .. 3.0 W when idle

    def watts(self, elapsed: float) -> float:
        drift = 1.0 + 0.04 * math.sin(elapsed / 7.0 + self.phase)   # slow +-4% wander
        jitter = random.gauss(0.0, self.base_w * 0.008)             # fast ~0.8% noise
        return max(0.0, self.base_w * drift + jitter)

    def standby_watts(self) -> float:
        return min(STANDBY_MAX_W, max(0.2, self.standby_w + random.gauss(0.0, 0.15)))


class VirtualPZEM:
    """Stand-in for the PZEM-004T: instantaneous readings plus an energy odometer."""

    def __init__(self) -> None:
        self._energy_joules = 0.0   # 1 W = 1 J/s

    @property
    def energy_wh(self) -> float:
        return self._energy_joules / 3600.0

    def sample(self, appliance, mode: str, elapsed: float, stray: Stray):
        """Return (raw_watts, gated_watts, voltage, current)."""
        if appliance is None:
            raw = stray.watts if stray.active else random.uniform(0.0, 3.0)   # spike or ghost noise
        elif mode == "standby":
            raw = appliance.standby_watts()          # plugged in but not in use
        else:
            raw = appliance.watts(elapsed)

        watts = 0.0 if raw < NOISE_GATE_W else raw   # noise gate

        voltage = min(PZEM_MAX_V, max(PZEM_MIN_V, random.gauss(NOMINAL_VOLTAGE, 0.8)))
        current = watts / voltage if watts > 0 else 0.0
        return raw, watts, voltage, current

    def accumulate(self, watts: float, dt: float) -> None:
        self._energy_joules += watts * dt


# --------------------------------------------------------------------------- #
# Simulated Wi-Fi pipeline
# --------------------------------------------------------------------------- #
def push_session(payload: dict, backend_url: str) -> None:
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        backend_url,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    log("WIFI", GREEN, f"pushing session summary -> {backend_url}")
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            log("WIFI", GREEN, f"backend accepted session (HTTP {response.status})")
    except urllib.error.HTTPError as exc:
        log("WIFI", RED, f"backend replied HTTP {exc.code}; printing payload instead")
        _print_payload(payload)
    except (urllib.error.URLError, OSError, TimeoutError) as exc:
        reason = getattr(exc, "reason", exc)
        log("WIFI", RED, f"backend unreachable ({reason}); printing payload instead")
        _print_payload(payload)


def _print_payload(payload: dict) -> None:
    with _print_lock:
        print(f"{GREEN}{json.dumps(payload, indent=2)}{RESET}", flush=True)


# --------------------------------------------------------------------------- #
# Adapter firmware: always-on 1 Hz loop (the ESP32 "main loop")
#
# All firmware state is touched ONLY by the firmware thread. The GUI talks to it
# through a command queue (plug / remove) and reads a published status snapshot,
# so the two never block each other.
# --------------------------------------------------------------------------- #
class AdapterFirmware:
    def __init__(self, telemetry: TelemetryState, backend_url: str, stray_interval_s: float) -> None:
        self._telemetry = telemetry
        self._backend_url = backend_url
        self._pzem = VirtualPZEM()
        self._stray = StrayCurrentModel(stray_interval_s)
        self._stop = threading.Event()
        self._wake = threading.Event()            # wakes the loop early for GUI commands
        self._commands = queue.Queue()
        self._thread = threading.Thread(target=self._run, name="firmware", daemon=True)

        self._status_lock = threading.Lock()
        self._status = self._build_status()

        # What is physically plugged in right now.
        self._tag = None
        self._mode = "active"
        self._appliance = None
        self._plugged_at = 0.0
        self._plug_until = None
        self._session_seen = False

        # Session state. The appliance id is latched in RAM together with the
        # starting Wh when the session starts; nothing here is ever persisted.
        self._session_tag = None
        self._start_wh = None
        self._start_ts = None
        self._end_ts = None
        self._end_wh = None
        self._cooldown_started = None
        self._push_threads = []

    # ---- thread-safe API (GUI thread) ----------------------------------- #
    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._wake.set()
        self._thread.join(timeout=2)
        if self._start_wh is not None:
            hw(RED, f"simulator stopped with an open session ({self._session_tag}); it was not sent")
        for thread in self._push_threads:
            thread.join(timeout=6)

    def plug(self, tag: str, duration_s: float, mode: str) -> None:
        self._commands.put(("plug", tag, duration_s, mode))
        self._wake.set()

    def remove(self) -> None:
        self._commands.put(("remove",))
        self._wake.set()

    def status(self) -> dict:
        with self._status_lock:
            return dict(self._status)

    # ---- loop ------------------------------------------------------------ #
    def _run(self) -> None:
        start = time.monotonic()
        next_tick = start - 1.0       # first tick fires immediately
        last = start - 1.0
        while not self._stop.is_set():
            next_tick += 1.0
            while True:
                delay = next_tick - time.monotonic()
                if delay < -2.0:      # resync after a long stall (e.g. laptop sleep)
                    next_tick = time.monotonic()
                    delay = 0.0
                if delay <= 0:
                    break
                self._wake.wait(delay)           # returns early on a GUI command or stop
                if self._stop.is_set():
                    return
                if self._wake.is_set():
                    self._wake.clear()
                    self._drain_commands()
            self._drain_commands()
            now = time.monotonic()
            dt = now - last
            last = now
            try:
                self._tick(now, dt)
            except Exception as exc:  # keep the loop alive no matter what
                log("SIM", RED, f"firmware tick error: {exc!r}")

    def _drain_commands(self) -> None:
        while True:
            try:
                command = self._commands.get_nowait()
            except queue.Empty:
                return
            try:
                if command[0] == "plug":
                    self._do_plug(command[1], command[2], command[3])
                elif command[0] == "remove":
                    self._do_remove()
            except Exception as exc:
                log("SIM", RED, f"command error: {exc!r}")
            self._publish_status()

    # ---- commands (run on the firmware thread) --------------------------- #
    def _do_plug(self, tag: str, duration_s: float, mode: str) -> None:
        if self._tag is not None:
            hw(RED, f"outlet already occupied by {self._tag}, plug ignored")
            return

        continuing = False
        if self._start_wh is not None:            # a session is waiting out its cooldown
            if tag == self._session_tag:
                continuing = True
                self._cooldown_started = None
                self._end_ts = None
                self._end_wh = None
                hw(YELLOW, f"same appliance ({tag}) plugged back in, session continues")
            else:
                hw(YELLOW, f"different appliance ({tag}) plugged in during cooldown, "
                           f"posting the previous session ({self._session_tag}) now")
                self._finalize_session()

        now = time.monotonic()
        self._tag = tag
        self._mode = mode
        self._appliance = ApplianceProfile(tag)
        self._plugged_at = now
        self._plug_until = now + duration_s
        self._session_seen = continuing
        state = "ACTIVE" if mode == "active" else "STANDBY (plugged in, not in use)"
        hw(YELLOW, f"RFID scanned: {tag} | plugged in for {duration_s:g}s | {state}")

    def _do_remove(self) -> None:
        if self._tag is None:
            hw(RED, "nothing is plugged in, nothing to remove")
            return
        self._unplug(time.monotonic(), "appliance removed by user (RFID tag out of range)")

    # ---- helpers ----------------------------------------------------------- #
    def _unplug(self, now: float, reason: str) -> None:
        self._tag = None
        self._appliance = None
        self._plug_until = None
        hw(YELLOW, reason)
        if self._start_wh is not None:
            self._begin_cooldown(now)
        elif not self._session_seen:
            hw(YELLOW, "load never crossed the noise gate while plugged in, nothing recorded")

    def _begin_cooldown(self, now: float) -> None:
        self._cooldown_started = now
        self._end_ts = iso_now()
        self._end_wh = self._pzem.energy_wh        # session energy ends when the power dropped
        hw(YELLOW, f"power dropped to 0 W, starting {COOLDOWN_S}s cooldown")

    def _build_status(self) -> dict:
        tag = getattr(self, "_tag", None)
        cooldown_started = getattr(self, "_cooldown_started", None)
        session_open = getattr(self, "_start_wh", None) is not None
        return {
            "plugged": tag is not None,
            "tag": tag,
            "mode": getattr(self, "_mode", "active"),
            "plug_until": getattr(self, "_plug_until", None),
            "session_tag": getattr(self, "_session_tag", None) if session_open else None,
            "cooldown_deadline": (cooldown_started + COOLDOWN_S) if cooldown_started is not None else None,
        }

    def _publish_status(self) -> None:
        status = self._build_status()
        with self._status_lock:
            self._status = status

    # ---- the 1 Hz tick ----------------------------------------------------- #
    def _tick(self, now: float, dt: float) -> None:
        if self._tag is not None and self._plug_until is not None and now >= self._plug_until:
            self._unplug(now, "timer finished, appliance unplugged (RFID tag out of range)")

        tag = self._tag
        # Stray current only exists on an empty outlet.
        stray = self._stray.step(allowed=(tag is None))
        raw, watts, voltage, current = self._pzem.sample(
            self._appliance, self._mode, now - self._plugged_at, stray)

        # Session state machine. Readings with no RFID tag (ghost noise, stray current)
        # never count: they cannot start a session, extend a cooldown, or be billed.
        session_watts = watts if tag is not None else 0.0
        events = []
        close_session = False

        if self._start_wh is None:
            if session_watts > 0:
                self._start_session(tag)
        elif session_watts > 0:
            if self._cooldown_started is not None:
                events.append("power resumed, cooldown cancelled (duty cycle / standby)")
            self._cooldown_started = None
            self._end_ts = None
            self._end_wh = None
        elif self._cooldown_started is None:
            self._begin_cooldown(now)
        else:
            waited = now - self._cooldown_started
            if waited >= COOLDOWN_S - 0.25:
                close_session = True
            else:
                events.append(f"cooldown {max(0, COOLDOWN_S - waited):.0f}s remaining")

        self._pzem.accumulate(watts, dt)
        self._telemetry.update(tag if tag is not None else NO_APPLIANCE, watts, voltage, current)

        # One status line per second, idle included.
        in_session = self._start_wh is not None
        phase = f"{now - self._plugged_at:5.1f}s" if (tag is not None or in_session) else " idle "
        color = DIM + YELLOW
        notes = ""
        if stray.active:
            color = ORANGE
            if watts > 0:
                notes = f" | stray current {stray.watts:.1f} W crossed the gate, ignored (no RFID tag)"
            else:
                notes = f" | stray current {stray.watts:.1f} W below the gate -> 0 W"
        elif 0 < raw < NOISE_GATE_W and (tag is not None or in_session):
            notes = f" | noise gate: {raw:.1f} W -> 0 W"
        hw(color,
           f"tick {phase} | rfid={tag if tag is not None else NO_APPLIANCE} | "
           f"{watts:7.1f} W | {voltage:5.1f} V | {current:.3f} A | "
           f"odometer {self._pzem.energy_wh:.4f} Wh{notes}")

        for message in events:
            hw(YELLOW, message)
        if close_session:
            self._finalize_session()
        self._publish_status()

    def _start_session(self, tag: str) -> None:
        self._session_tag = tag                      # appliance id and starting Wh, both in RAM
        self._start_wh = self._pzem.energy_wh
        self._start_ts = iso_now()
        self._end_ts = None
        self._end_wh = None
        self._cooldown_started = None
        self._session_seen = True
        hw(YELLOW,
           f"load detected (> {NOISE_GATE_W:g} W) with id {tag}, session started | "
           f"Starting Wh locked in RAM: {self._start_wh:.4f}")

    def _finalize_session(self) -> None:
        end_wh = self._end_wh if self._end_wh is not None else self._pzem.energy_wh
        delta_wh = end_wh - self._start_wh
        hw(YELLOW,
           f"session closed ({self._session_tag}) | start {self._start_wh:.4f} Wh -> "
           f"end {end_wh:.4f} Wh | delta {delta_wh:.4f} Wh")

        if delta_wh < MIN_SESSION_WH:
            hw(RED, f"delta below {MIN_SESSION_WH} Wh, session discarded (not sent)")
        else:
            payload = {
                "outlet_id": OUTLET_ID,
                "appliance_rfid": self._session_tag,
                "start_timestamp": self._start_ts,
                "end_timestamp": self._end_ts,
                "total_wh_consumed": round(delta_wh, 4),
            }
            # Push from its own thread so a slow backend never stalls the 1 Hz loop.
            thread = threading.Thread(
                target=push_session, args=(payload, self._backend_url),
                name="wifi-push", daemon=True)
            thread.start()
            self._push_threads.append(thread)

        self._session_tag = None
        self._start_ts = None
        self._end_ts = None
        self._end_wh = None
        self._cooldown_started = None
        self._start_wh = None


# --------------------------------------------------------------------------- #
# GUI control panel (runs on the main thread; never touches firmware state directly)
# --------------------------------------------------------------------------- #
class ControlPanel:
    REFRESH_MS = 200

    def __init__(self, root, firmware: AdapterFirmware, telemetry: TelemetryState) -> None:
        self._root = root
        self._fw = firmware
        self._telemetry = telemetry

        root.title("Kilowhat Outlet Control Panel")
        root.resizable(False, False)

        base = tkfont.nametofont("TkDefaultFont")
        bold = base.copy()
        bold.configure(weight="bold")
        big = base.copy()
        big.configure(weight="bold", size=int(abs(base.cget("size")) + 4))
        small = base.copy()
        small.configure(size=int(abs(base.cget("size")) - 1))
        self._fonts = (bold, big, small)   # keep references so Tk does not drop them

        self.id_var = tk.StringVar(value="")
        self.sec_var = tk.StringVar(value="30")
        self.mode_var = tk.StringVar(value="active")
        self.state_var = tk.StringVar(value="Outlet empty")
        self.detail_var = tk.StringVar(value="Nothing is plugged in.")
        self.pair_var = tk.StringVar(value="Phone: not paired")
        self.error_var = tk.StringVar(value="")

        outer = ttk.Frame(root, padding=16)
        outer.grid(row=0, column=0)

        header = ttk.Frame(outer)
        header.grid(row=0, column=0, sticky="ew")
        ttk.Label(header, text=f"Outlet {OUTLET_ID}", font=bold).pack(side="left")
        self._pair_label = ttk.Label(header, textvariable=self.pair_var)
        self._pair_label.pack(side="right")

        status = ttk.LabelFrame(outer, text="Outlet status", padding=12)
        status.grid(row=1, column=0, sticky="ew", pady=(12, 8))
        self._state_label = ttk.Label(status, textvariable=self.state_var, font=big)
        self._state_label.grid(row=0, column=0, sticky="w")
        ttk.Label(status, textvariable=self.detail_var, wraplength=380,
                  justify="left").grid(row=1, column=0, sticky="w", pady=(4, 0))

        controls = ttk.LabelFrame(outer, text="Plug an appliance", padding=12)
        controls.grid(row=2, column=0, sticky="ew")
        ttk.Label(controls, text="Appliance ID").grid(row=0, column=0, sticky="w")
        self._id_entry = ttk.Entry(controls, textvariable=self.id_var, width=30)
        self._id_entry.grid(row=0, column=1, sticky="w", padx=(10, 0))
        ttk.Label(controls, text=f"Leave empty to use '{UNKNOWN_ID}'",
                  font=small).grid(row=1, column=1, sticky="w", padx=(10, 0))
        ttk.Label(controls, text="Seconds").grid(row=2, column=0, sticky="w", pady=(8, 0))
        self._sec_entry = ttk.Entry(controls, textvariable=self.sec_var, width=10)
        self._sec_entry.grid(row=2, column=1, sticky="w", padx=(10, 0), pady=(8, 0))
        ttk.Label(controls, text="State").grid(row=3, column=0, sticky="w", pady=(8, 0))
        mode_row = ttk.Frame(controls)
        mode_row.grid(row=3, column=1, sticky="w", padx=(10, 0), pady=(8, 0))
        ttk.Radiobutton(mode_row, text="Active", value="active",
                        variable=self.mode_var).pack(side="left")
        ttk.Radiobutton(mode_row, text="Standby", value="standby",
                        variable=self.mode_var).pack(side="left", padx=(12, 0))

        buttons = ttk.Frame(outer)
        buttons.grid(row=3, column=0, sticky="ew", pady=(12, 0))
        self._plug_btn = ttk.Button(buttons, text="Plug in appliance", command=self._on_plug)
        self._plug_btn.pack(side="left")
        self._remove_btn = ttk.Button(buttons, text="Remove appliance", command=self._on_remove)
        self._remove_btn.pack(side="left", padx=(8, 0))

        self._error_label = ttk.Label(outer, textvariable=self.error_var, foreground="#b00020")
        self._error_label.grid(row=4, column=0, sticky="w", pady=(8, 0))

        self._id_entry.bind("<Return>", lambda _e: self._on_plug())
        self._sec_entry.bind("<Return>", lambda _e: self._on_plug())
        self._id_entry.focus_set()

        self._refresh()

    # ---- actions --------------------------------------------------------- #
    def _on_plug(self) -> None:
        if self._plug_btn.instate(["disabled"]):
            return
        tag = self.id_var.get().strip() or UNKNOWN_ID
        if len(tag) > MAX_ID_LEN:
            self.error_var.set(f"Appliance ID is too long (max {MAX_ID_LEN} characters).")
            return
        try:
            seconds = float(self.sec_var.get().strip())
        except ValueError:
            self.error_var.set("Seconds must be a number, e.g. 30.")
            return
        if not math.isfinite(seconds) or seconds <= 0:
            self.error_var.set("Seconds must be greater than 0.")
            return
        self.error_var.set("")
        self._fw.plug(tag, seconds, self.mode_var.get())

    def _on_remove(self) -> None:
        if self._remove_btn.instate(["disabled"]):
            return
        self._fw.remove()

    # ---- periodic refresh (polls the firmware status snapshot) ------------ #
    def _refresh(self) -> None:
        try:
            snap = self._fw.status()
            now = time.monotonic()
            paired = self._telemetry.is_paired()
            self.pair_var.set("Phone: paired" if paired else "Phone: not paired")
            self._pair_label.configure(foreground="#0a7d33" if paired else "#6b6b6b")

            if snap["plugged"]:
                until = snap["plug_until"]
                left = f"{max(0.0, until - now):.0f}s left" if until is not None else "no timer"
                self.state_var.set(f"Plugged in: {snap['tag']}")
                self.detail_var.set(f"{snap['mode'].capitalize()} | {left}")
                self._state_label.configure(foreground="#0a7d33")
                self._plug_btn.state(["disabled"])
                self._remove_btn.state(["!disabled"])
            elif snap["cooldown_deadline"] is not None:
                left = max(0.0, snap["cooldown_deadline"] - now)
                self.state_var.set("Appliance removed")
                self.detail_var.set(
                    f"Session for '{snap['session_tag']}' closes in {left:.1f}s. "
                    f"Plug the same ID to continue it, or a different ID to post it and start a new one.")
                self._state_label.configure(foreground="#c25e00")
                self._plug_btn.state(["!disabled"])
                self._remove_btn.state(["disabled"])
            else:
                self.state_var.set("Outlet empty")
                self.detail_var.set("Nothing is plugged in.")
                self._state_label.configure(foreground="#6b6b6b")
                self._plug_btn.state(["!disabled"])
                self._remove_btn.state(["disabled"])
        except Exception as exc:  # never let a display glitch kill the refresh loop
            log("SIM", RED, f"GUI refresh error: {exc!r}")
        finally:
            try:
                self._root.after(self.REFRESH_MS, self._refresh)
            except tk.TclError:
                pass  # window already closed


# --------------------------------------------------------------------------- #
# Entry point
# --------------------------------------------------------------------------- #
def main() -> None:
    global _telemetry_ref

    parser = argparse.ArgumentParser(description="Kilowhat adapter-box simulator")
    parser.add_argument("--host", default="0.0.0.0", help="bind address for the BLE-over-HTTP server")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="BLE-over-HTTP port (default 8001)")
    parser.add_argument("--backend-url", default=DEFAULT_BACKEND_URL, help="Kotlin backend session endpoint")
    parser.add_argument("--stray-interval", "--surge-interval", dest="stray_interval", type=float,
                        default=DEFAULT_STRAY_INTERVAL_S,
                        help="average seconds between stray-current spikes on an empty outlet "
                             "(default 40, 0 disables)")
    args = parser.parse_args()

    if tk is None:
        print(f"{RED}tkinter is not available, so the control panel cannot open. "
              f"On Linux install the python3-tk package.{RESET}")
        sys.exit(1)
    try:
        root = tk.Tk()
    except tk.TclError as exc:
        print(f"{RED}Could not open the control panel window: {exc}{RESET}")
        sys.exit(1)

    telemetry = TelemetryState()
    _telemetry_ref = telemetry
    try:
        server = ThreadingHTTPServer((args.host, args.port), make_handler(telemetry))
    except OSError as exc:
        print(f"{RED}Could not start BLE server on {args.host}:{args.port}: {exc}{RESET}")
        root.destroy()
        sys.exit(1)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="ble-http", daemon=True).start()

    firmware = AdapterFirmware(telemetry, args.backend_url, args.stray_interval)
    firmware.start()
    ControlPanel(root, firmware, telemetry)

    stray_text = f"about every {args.stray_interval:g}s" if args.stray_interval > 0 else "off"
    with _print_lock:
        print(f"{BOLD}Kilowhat adapter simulator{RESET} | node {OUTLET_ID}")
        print(f"  {CYAN}BLE (HTTP){RESET}  http://localhost:{args.port}  (POST /pair, GET /ble-stream)")
        print(f"  {GREEN}Wi-Fi POST{RESET}  {args.backend_url}")
        print(f"  Control panel window: plug / remove appliances there. Logs stay here.")
        print(f"  Log prefix [HW ][# BLE]: # = phones paired (0 or 1).")
        print(f"  Idle simulation is always running (line voltage ~230 V, "
              f"stray current on an empty outlet: {stray_text}).")
        print(f"  Close the window or press Ctrl+C to quit.\n", flush=True)

    try:
        root.mainloop()
    except KeyboardInterrupt:
        print()
    finally:
        try:
            root.destroy()
        except tk.TclError:
            pass
        firmware.stop()
        server.shutdown()
        server.server_close()
        log("SIM", MAGENTA, "simulator stopped")


if __name__ == "__main__":
    main()
