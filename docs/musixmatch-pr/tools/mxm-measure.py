#!/usr/bin/env python3
"""
Pengukuran terbatas API Musixmatch: dingin vs hangat, dan degradasi antar-pencarian.

BUKAN stress test. Volume setara beberapa pencarian manual.
Berhenti pada tanda throttle pertama (401/429), tidak mendorong lebih jauh.
Tidak pernah mencetak token, URL lengkap, response body, atau lirik.
"""
import json, time, urllib.request, urllib.parse, urllib.error
from concurrent.futures import ThreadPoolExecutor
import threading

BASE = "https://apic-appmobile.musixmatch.com/ws/1.1/"
HEADERS = {
    "Accept": "application/json",
    "Accept-Language": "en-US,en;q=0.9",
    "X-Cookie": "x-mxm-token-guid=",
    "x-mxm-app-version": "10.1.1",
    "X-User-Agent": "Musixmatch/2025120901 CFNetwork/3860.300.31 Darwin/25.2.0",
}
APP_ID = "mac-ios-v2.0"
MAX_REQUESTS = 30
request_count = 0
throttled = None
stages = []


class Throttle(Exception):
    pass


def req(endpoint, params):
    global request_count, throttled
    if throttled:
        raise Throttle(throttled)
    if request_count >= MAX_REQUESTS:
        raise Throttle("budget request tercapai")
    request_count += 1
    q = urllib.parse.urlencode({"app_id": APP_ID, **params})
    r = urllib.request.Request(BASE + endpoint + "?" + q, headers=HEADERS)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(r, timeout=12) as resp:
            http = resp.status
            raw = resp.read()
    except urllib.error.HTTPError as e:
        http = e.code
        raw = b""
        if http in (401, 429):
            throttled = f"HTTP {http} pada {endpoint}"
            raise Throttle(throttled)
    ms = round((time.perf_counter() - t0) * 1000)
    if not raw:
        raise RuntimeError(f"{endpoint}: body kosong")
    try:
        data = json.loads(raw)
    except Exception:
        raise RuntimeError(f"{endpoint}: JSON tidak valid")
    status = (data.get("message", {}).get("header", {}) or {}).get("status_code")
    stages.append({"endpoint": endpoint, "http": http, "api": status, "ms": ms})
    if status in (401, 429):
        throttled = f"api {status} pada {endpoint}"
        raise Throttle(throttled)
    if status != 200:
        raise RuntimeError(f"{endpoint}: api status {status}")
    return data["message"].get("body") or {}, ms


def usable_lines(body, kind="richsync"):
    """Perkiraan kelayakan INPUT, bukan verifikasi render produksi.

    richsync : {ts, te, l[], x} -> guard MIN_SPAN berlaku
    subtitle : {text, time.total} -> tidak punya ts/te; guard MIN_SPAN TIDAK berlaku
    """
    try:
        arr = json.loads(body) if isinstance(body, str) else body
    except Exception:
        return 0
    if not isinstance(arr, list):
        return 0
    if kind == "subtitle":
        return sum(1 for ln in arr
                   if isinstance(ln, dict) and (ln.get("text") or "").strip())
    return sum(
        1 for ln in arr
        if isinstance(ln, dict)
        and (ln.get("te", 0) - ln.get("ts", 0)) >= 0.002
        and (any((w.get("c") or "").strip() for w in (ln.get("l") or []))
             or (ln.get("x") or "").strip())
    )


def one_search(token, title, artist, label, concurrency=2, cap=3):
    rec = {"label": label, "candidates": []}
    body, search_ms = req("track.search", {
        "usertoken": token, "q_track": title, "q_artist": artist,
        "q_artists": artist, "page_size": str(cap), "f_has_lyrics": "1",
    })
    rec["searchMs"] = search_ms
    tracks = [i.get("track") or {} for i in (body.get("track_list") or [])]
    eligible = [t for t in tracks
                if t.get("track_id", 0) > 0 and t.get("instrumental") != 1][:cap]
    rec["returned"] = len(tracks)
    rec["eligible"] = len(eligible)
    t0 = time.perf_counter()
    first_ms = None
    first_lock = threading.Lock()

    def fetch(t):
        nonlocal first_ms
        try:
            b, ms = req("macro.subtitles.get", {
                "usertoken": token, "track_id": str(t["track_id"]),
                "format": "json", "namespace": "lyrics_richsynched",
                "subtitle_format": "mxm", "optional_calls": "track.richsync",
                "richsync_compact_type": "words",
            })
            calls = b.get("macro_calls") or {}
            def msg(k):
                return (calls.get(k) or {}).get("message") or {}
            matcher = msg("matcher.track.get")
            matched = ((matcher.get("body") or {}).get("track") or {}).get("track_id")
            rich, sub, plain = msg("track.richsync.get"), msg("track.subtitles.get"), msg("track.lyrics.get")
            rl = usable_lines(((rich.get("body") or {}).get("richsync") or {}).get("richsync_body")) \
                if rich.get("header", {}).get("status_code") == 200 else 0
            sl = usable_lines((((sub.get("body") or {}).get("subtitle_list") or [{}])[0]
                               .get("subtitle") or {}).get("subtitle_body"), "subtitle") \
                if sub.get("header", {}).get("status_code") == 200 else 0
            has_plain = bool((((plain.get("body") or {}).get("lyrics") or {})
                              .get("lyrics_body") or "").strip())
            fmt = "richsync" if rl else ("subtitle" if sl else ("plain" if has_plain else None))
            with first_lock:
                if first_ms is None:
                    first_ms = round((time.perf_counter() - t0) * 1000)
            return {"matchedOk": matched == t["track_id"], "format": fmt,
                    "richsyncLines": rl, "subtitleLines": sl, "ms": ms}
        except Throttle:
            raise
        except Exception as e:
            return {"error": type(e).__name__}

    with ThreadPoolExecutor(max_workers=concurrency) as ex:
        rec["candidates"] = list(ex.map(fetch, eligible))
    rec["fanoutMs"] = round((time.perf_counter() - t0) * 1000)
    rec["firstResultMsAfterSearch"] = first_ms
    rec["ok"] = sum(1 for c in rec["candidates"] if c.get("format"))
    return rec


report = {"note": "bounded measurement, bukan stress test", "searches": []}
try:
    t0 = time.perf_counter()
    tb, token_ms = req("token.get", {})
    token = tb.get("user_token") or ""
    if not token or token.startswith("UpgradeOnly") or set(token) == {"0"}:
        raise RuntimeError("token tidak valid")
    report["tokenMs"] = token_ms
    report["tokenLength"] = len(token)

    plan = [("Bohemian Rhapsody", "Queen", "verifikasi subtitle")]
    for title, artist, label in plan:
        rec = one_search(token, title, artist, label)
        rec["totalMs"] = rec["searchMs"] + rec["fanoutMs"]
        report["searches"].append(rec)
        time.sleep(1.0)  # jeda wajar antar pencarian
except Throttle as e:
    report["stoppedEarly"] = str(e)
except Exception as e:
    report["error"] = type(e).__name__

report["totalRequests"] = request_count
report["throttled"] = throttled
report["tokenRequests"] = sum(1 for s in stages if s["endpoint"] == "token.get")
print(json.dumps(report, indent=2))
