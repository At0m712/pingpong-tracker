#!/usr/bin/env python3
"""
PingPong Tracker — Automatic backend generator for matches.json (GitHub Pages).

Sources (all 100% free, zero API keys):
1. YouTube Live & Upcoming streams from @FFTT_Officiel and @WTTGlobal (ytInitialData).
2. World Table Tennis (worldtabletennis.com) via Playwright headless Chromium,
   intercepting official WTT JSON API responses for live scores, schedules & draws.
3. Optional Pro A calendar / overrides in backend/manual_matches.json.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

PARIS_TZ = ZoneInfo("Europe/Paris")

TRACKED_PLAYERS = [
    "Félix Lebrun",
    "Alexis Lebrun",
    "Flavien Coton",
    "Thibault Poret",
    "Simon Gauzy",
]

TRACKED_PLAYER_KEYS = [
    "lebrun",
    "coton",
    "poret",
    "gauzy",
]

TRACKED_CLUB = "CTT Bruille"

PRO_A_CLUBS = [
    "CTT Bruille",
    "Bruille",
    "SPO Rouen",
    "Rouen",
    "AS Pontoise-Cergy",
    "Pontoise",
    "Alliance Nîmes",
    "Nîmes",
    "Montpellier",
    "GV Hennebont",
    "Hennebont",
    "Morez Haut-Jura",
    "Morez",
    "Les Loups d'Angers",
    "Angers",
    "C'Chartres TT",
    "Chartres",
    "Saint-Denis",
    "Thorigné-Fouillard",
    "Thorigné",
    "Roanne",
    "La Romagne",
]

# Regions / WTT events frequently geo-blocked in France on YouTube
VPN_KEYWORDS = [
    "india",
    "inde",
    "goa",
    "chennai",
    "mumbai",
    "china",
    "beijing",
    "chongqing",
    "macao",
    "wuhan",
    "singapore",
    "fukuoka",
    "yokohama",
    "incheon",
    "busan",
    "doha",
    "hong kong",
    "wtt finals",
    "wtt champions",
    "wtt star contender",
]

YOUTUBE_WTT = "https://www.youtube.com/@WTTGlobal/streams"
YOUTUBE_FFTT = "https://www.youtube.com/@FFTT_Officiel/streams"

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)


def slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")[:52]


def detect_vpn(text: str) -> tuple[bool, str]:
    lowered = text.lower()
    for kw in VPN_KEYWORDS:
        if kw in lowered:
            return True, "VPN conseillé (YouTube FR)"
    return False, ""


def is_tracked_player(name: str) -> bool:
    lowered = name.lower()
    return any(k in lowered for k in TRACKED_PLAYER_KEYS)


def matched_players(text: str) -> list[str]:
    found: list[str] = []
    combined = text.lower()
    for full_name in TRACKED_PLAYERS:
        last_name = full_name.split()[-1].lower()
        if last_name == "lebrun":
            if "félix" in combined or "felix" in combined or "f. lebrun" in combined or "lebrun f" in combined:
                if "Félix Lebrun" not in found:
                    found.append("Félix Lebrun")
            if "alexis" in combined or "a. lebrun" in combined or "lebrun a" in combined:
                if "Alexis Lebrun" not in found:
                    found.append("Alexis Lebrun")
            if "lebrun" in combined and "Félix Lebrun" not in found and "Alexis Lebrun" not in found:
                found.append("Lebrun")
        elif last_name in combined:
            if full_name not in found:
                found.append(full_name)
    return found


def matched_clubs(text: str) -> list[str]:
    lowered = text.lower()
    found: list[str] = []
    for club in PRO_A_CLUBS:
        if club.lower() in lowered:
            if club == "Bruille":
                club = "CTT Bruille"
            if club not in found:
                found.append(club)
    return found


# ---------------------------------------------------------------------------
# Source 1: YouTube Live & Upcoming Streams (@FFTT_Officiel & @WTTGlobal)
# ---------------------------------------------------------------------------

def _walk_video_renderers(node: object, out: list[dict]) -> None:
    if isinstance(node, dict):
        if "videoRenderer" in node and isinstance(node["videoRenderer"], dict):
            out.append(node["videoRenderer"])
        for v in node.values():
            _walk_video_renderers(v, out)
    elif isinstance(node, list):
        for item in node:
            _walk_video_renderers(item, out)


def _extract_yt_text(obj: object) -> str:
    if isinstance(obj, str):
        return obj
    if isinstance(obj, dict):
        if "simpleText" in obj:
            return str(obj["simpleText"])
        if "runs" in obj and isinstance(obj["runs"], list):
            return "".join(str(r.get("text", "")) for r in obj["runs"] if isinstance(r, dict))
    return ""


def fetch_youtube_channel_streams(
    channel_url: str,
    category: str,
    broadcast_label: str,
    now_paris: datetime,
) -> list[dict]:
    req = urllib.request.Request(
        channel_url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
            "Cookie": "CONSENT=YES+cb.20210328-17-p0.fr+FX+111",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=12) as resp:
            html = resp.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f"[warn] YouTube fetch failed ({channel_url}): {exc}", file=sys.stderr)
        return []

    m = re.search(r"var ytInitialData\s*=\s*(\{.*?\});</script>", html, re.DOTALL)
    if not m:
        print(f"[warn] ytInitialData not found on {channel_url}", file=sys.stderr)
        return []

    try:
        data = json.loads(m.group(1))
    except json.JSONDecodeError:
        return []

    renderers: list[dict] = []
    _walk_video_renderers(data, renderers)

    results: list[dict] = []
    for vr in renderers[:25]:
        video_id = str(vr.get("videoId") or "").strip()
        title = _extract_yt_text(vr.get("title")).strip()
        if not video_id or not title:
            continue

        # Determine LIVE vs UPCOMING
        is_live = False
        for badge in vr.get("badges", []) or []:
            style = (
                badge.get("metadataBadgeRenderer", {}).get("style", "")
                if isinstance(badge, dict)
                else ""
            )
            if "LIVE" in str(style).upper():
                is_live = True
        for ov in vr.get("thumbnailOverlays", []) or []:
            style = (
                ov.get("thumbnailOverlayTimeStatusRenderer", {}).get("style", "")
                if isinstance(ov, dict)
                else ""
            )
            if str(style).upper() == "LIVE":
                is_live = True

        upcoming = vr.get("upcomingEventData")
        start_dt: datetime | None = None
        if isinstance(upcoming, dict) and upcoming.get("startTime"):
            try:
                start_dt = datetime.fromtimestamp(int(upcoming["startTime"]), tz=PARIS_TZ)
            except (ValueError, TypeError):
                start_dt = None

        if not is_live and start_dt is None:
            continue  # Skip already-finished VODs

        players = matched_players(title)
        clubs = matched_clubs(title)

        # For WTT, keep only streams featuring tracked players or major live tables
        if category == "INTL" and not players and not is_live:
            continue
        # For FFTT, keep Pro A / tracked clubs / tracked players
        if category == "CLUB" and not clubs and not players and "pro a" not in title.lower():
            continue

        status = "live" if is_live else "scheduled"
        start_iso = (start_dt or now_paris).isoformat(timespec="seconds")

        # Try splitting "Team A vs Team B" or "Team A - Team B"
        home, away = title, ""
        sep_match = re.split(r"\s+(?:vs\.?|v\.?|-|–|/)\s+", title, maxsplit=1, flags=re.IGNORECASE)
        if len(sep_match) == 2 and len(sep_match[0]) <= 45 and len(sep_match[1]) <= 45:
            home, away = sep_match[0].strip(), sep_match[1].strip()
        else:
            away = broadcast_label

        followed = players + [c for c in clubs if c not in players]
        if not followed:
            followed = ["Pro A" if category == "CLUB" else "WTT Live"]

        vpn_req, vpn_note = (detect_vpn(title) if category == "INTL" else (False, ""))
        comp = "Pro A · Live YouTube" if category == "CLUB" else "WTT · Live YouTube"

        results.append(
            {
                "id": f"yt_{video_id}",
                "category": category,
                "competition": comp,
                "home": home[:64],
                "away": away[:64],
                "start_iso": start_iso,
                "status": status,
                "score": "",
                "followed": followed[:3],
                "broadcast": broadcast_label,
                "url": f"https://www.youtube.com/watch?v={video_id}",
                "vpn_required": vpn_req,
                "vpn_note": vpn_note,
            }
        )

    print(f"[ok] YouTube {broadcast_label}: {len(results)} live/upcoming streams found")
    return results


# ---------------------------------------------------------------------------
# Source 2: World Table Tennis via Playwright (network JSON interception)
# ---------------------------------------------------------------------------

def _parse_wtt_match_object(m: dict, event_name: str, vpn_req: bool, vpn_note: str, now_paris: datetime) -> dict | None:
    if not isinstance(m, dict):
        return None
    # WTT JSON schemas vary slightly across endpoints; handle common field names
    p1 = (
        m.get("homePlayerName")
        or m.get("player1Name")
        or m.get("competitor1Name")
        or (m.get("competitors", [{}])[0].get("competitorName") if isinstance(m.get("competitors"), list) and len(m.get("competitors", [])) >= 2 else "")
        or ""
    )
    p2 = (
        m.get("awayPlayerName")
        or m.get("player2Name")
        or m.get("competitor2Name")
        or (m.get("competitors", [{}, {}])[1].get("competitorName") if isinstance(m.get("competitors"), list) and len(m.get("competitors", [])) >= 2 else "")
        or ""
    )
    p1, p2 = str(p1).strip(), str(p2).strip()
    if not p1 or not p2:
        return None

    players = matched_players(f"{p1} {p2}")
    if not players:
        return None

    raw_status = str(m.get("matchStatus") or m.get("status") or m.get("resultStatus") or "").lower()
    if any(k in raw_status for k in ("live", "running", "inprogress", "in_progress", "playing")):
        status = "live"
    elif any(k in raw_status for k in ("official", "finished", "complete", "ended")):
        status = "finished"
    else:
        status = "scheduled"

    s1 = str(m.get("homeScore") or m.get("score1") or m.get("overallScore1") or "").strip()
    s2 = str(m.get("awayScore") or m.get("score2") or m.get("overallScore2") or "").strip()
    score = f"{s1} - {s2}" if s1 and s2 else str(m.get("overallScores") or "").strip()

    raw_time = str(m.get("matchDateTime") or m.get("startTime") or m.get("scheduledTime") or "").strip()
    start_iso = now_paris.isoformat(timespec="seconds")
    if raw_time:
        try:
            dt = datetime.fromisoformat(raw_time.replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=PARIS_TZ)
            start_iso = dt.astimezone(PARIS_TZ).isoformat(timespec="seconds")
        except ValueError:
            pass

    round_name = str(m.get("subEventDescription") or m.get("roundName") or m.get("phaseName") or "").strip()
    comp = f"{event_name} · {round_name}" if round_name else event_name
    match_id = str(m.get("documentCode") or m.get("matchId") or f"wtt_{slugify(comp)}_{slugify(p1)}_{slugify(p2)}")

    return {
        "id": f"wtt_{slugify(match_id)}",
        "category": "INTL",
        "competition": comp[:60],
        "home": p1,
        "away": p2,
        "start_iso": start_iso,
        "status": status,
        "score": score,
        "followed": players,
        "broadcast": "YouTube WTT",
        "url": YOUTUBE_WTT,
        "vpn_required": vpn_req,
        "vpn_note": vpn_note,
    }


def _collect_match_dicts(payload: object, out: list[dict]) -> None:
    if isinstance(payload, dict):
        keys = set(payload.keys())
        if (
            {"homePlayerName", "awayPlayerName"} <= keys
            or {"player1Name", "player2Name"} <= keys
            or {"competitor1Name", "competitor2Name"} <= keys
            or ("competitors" in keys and isinstance(payload.get("competitors"), list))
        ):
            out.append(payload)
        for v in payload.values():
            _collect_match_dicts(v, out)
    elif isinstance(payload, list):
        for item in payload:
            _collect_match_dicts(item, out)


def fetch_wtt_via_playwright(now_paris: datetime) -> list[dict]:
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("[info] Playwright not installed; skipping WTT browser scrape", file=sys.stderr)
        return []

    captured_payloads: list[tuple[str, object]] = []

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)
            context = browser.new_context(user_agent=USER_AGENT, locale="fr-FR")
            page = context.new_page()

            def on_response(response) -> None:
                url = response.url.lower()
                if any(k in url for k in ("wttcmsapigateway", "liveeventsapi", "wttapigateway", "getmatch", "livescore", "getofficial")):
                    if response.status == 200:
                        try:
                            captured_payloads.append((response.url, response.json()))
                        except Exception:
                            pass

            page.on("response", on_response)

            for target in ("https://worldtabletennis.com/livescores", "https://worldtabletennis.com/eventslist"):
                try:
                    page.goto(target, wait_until="networkidle", timeout=25_000)
                    page.wait_for_timeout(2000)
                except Exception as exc:
                    print(f"[warn] Playwright goto {target}: {exc}", file=sys.stderr)

            browser.close()
    except Exception as exc:
        print(f"[warn] Playwright run failed: {exc}", file=sys.stderr)
        return []

    raw_matches: list[dict] = []
    for _, payload in captured_payloads:
        _collect_match_dicts(payload, raw_matches)

    parsed: list[dict] = []
    for rm in raw_matches:
        ev_name = str(rm.get("eventName") or rm.get("tournamentName") or "WTT").strip()
        vpn_req, vpn_note = detect_vpn(ev_name)
        item = _parse_wtt_match_object(rm, ev_name, vpn_req, vpn_note, now_paris)
        if item:
            parsed.append(item)

    print(f"[ok] WTT Playwright: {len(captured_payloads)} API responses, {len(parsed)} tracked matches")
    return parsed


# ---------------------------------------------------------------------------
# Manual overrides & merge
# ---------------------------------------------------------------------------

def load_manual_overrides(path: Path) -> tuple[list[dict], list[dict]]:
    if not path.exists():
        return [], []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return list(data.get("matches", [])), list(data.get("tournaments", []))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[warn] Could not read {path}: {exc}", file=sys.stderr)
        return [], []


def merge_by_id(primary: list[dict], secondary: list[dict]) -> list[dict]:
    seen: dict[str, dict] = {}
    for item in secondary:
        item_id = str(item.get("id") or "")
        if item_id:
            seen[item_id] = item
    for item in primary:
        item_id = str(item.get("id") or "")
        if item_id:
            seen[item_id] = item
    return list(seen.values())


def prune_old_finished(matches: list[dict], now_paris: datetime) -> list[dict]:
    cutoff = now_paris - timedelta(days=7)
    kept: list[dict] = []
    for m in matches:
        if str(m.get("status", "")).lower() != "finished":
            kept.append(m)
            continue
        try:
            dt = datetime.fromisoformat(str(m.get("start_iso", "")).replace("Z", "+00:00"))
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=PARIS_TZ)
            if dt >= cutoff:
                kept.append(m)
        except ValueError:
            kept.append(m)
    return kept


def write_status_index(site_dir: Path, updated_label: str, match_count: int, tournament_count: int) -> None:
    template_path = Path(__file__).parent / "site_template.html"
    if template_path.exists():
        html = template_path.read_text(encoding="utf-8").replace("__UPDATED_LABEL__", updated_label)
    else:
        html = f"<h1>PingPong Tracker</h1><p>MAJ {updated_label} · {match_count} matchs</p>"
    (site_dir / "index.html").write_text(html, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate matches.json for PingPong Tracker")
    parser.add_argument("--out", default="site/matches.json", help="Output JSON path")
    parser.add_argument(
        "--manual",
        default="backend/manual_matches.json",
        help="Optional manual overrides JSON file",
    )
    args = parser.parse_args()

    now_paris = datetime.now(PARIS_TZ)
    updated_at = now_paris.isoformat(timespec="seconds")
    updated_label = now_paris.strftime("%d/%m %H:%M")

    # 1. Auto-scraped live & upcoming YouTube streams (FFTT + WTT)
    yt_fftt = fetch_youtube_channel_streams(YOUTUBE_FFTT, "CLUB", "YouTube FFTT", now_paris)
    yt_wtt = fetch_youtube_channel_streams(YOUTUBE_WTT, "INTL", "YouTube WTT", now_paris)

    # 2. Auto-scraped WTT live scores & schedules via Playwright
    wtt_matches = fetch_wtt_via_playwright(now_paris)

    # 3. Base calendar & tournament brackets
    manual_matches, manual_tournaments = load_manual_overrides(Path(args.manual))

    scraped = yt_fftt + yt_wtt + wtt_matches
    all_matches = prune_old_finished(merge_by_id(scraped, manual_matches), now_paris)

    for m in all_matches:
        if "vpn_required" not in m:
            vpn_req, vpn_note = detect_vpn(f"{m.get('competition', '')} {m.get('broadcast', '')}")
            m["vpn_required"] = vpn_req
            m["vpn_note"] = vpn_note

    payload = {
        "updated_at": updated_at,
        "updated_label": updated_label,
        "tracked_players": TRACKED_PLAYERS,
        "tracked_club": TRACKED_CLUB,
        "matches": all_matches,
        "tournaments": manual_tournaments,
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    write_status_index(out_path.parent, updated_label, len(all_matches), len(manual_tournaments))
    print(f"[ok] Wrote {out_path} ({len(all_matches)} matches, {len(manual_tournaments)} tournaments)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
