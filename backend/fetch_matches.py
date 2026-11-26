#!/usr/bin/env python3
"""
PingPong Tracker — Backend generator for matches.json (GitHub Pages).
Fetches WTT / ITTF international matches & tournament brackets + FFTT Pro A Messieurs,
merges optional manual_matches.json overrides, and writes a clean matches.json.
Uses Python 3 standard library only (zero pip dependencies).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
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
    "SPO Rouen",
    "AS Pontoise-Cergy",
    "Alliance Nîmes",
    "GV Hennebont",
    "Morez Haut-Jura",
    "Les Loups d'Angers",
    "C'Chartres TT",
    "Saint-Denis US 93",
    "Thorigné-Fouillard",
]

# Regions / events frequently geo-blocked in France on WTT YouTube
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
]

YOUTUBE_WTT = "https://www.youtube.com/@WTTGlobal/streams"
YOUTUBE_FFTT = "https://www.youtube.com/@FFTT_Officiel/streams"

USER_AGENT = "Mozilla/5.0 (compatible; PingPongTrackerBot/1.0)"


def http_get_json(url: str, timeout: int = 12) -> object | None:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": USER_AGENT,
            "Accept": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            return json.loads(raw)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as exc:
        print(f"[warn] GET {url} failed: {exc}", file=sys.stderr)
        return None


def detect_vpn(text: str) -> tuple[bool, str]:
    lowered = text.lower()
    for kw in VPN_KEYWORDS:
        if kw in lowered:
            return True, "VPN conseillé (géo-bloqué FR)"
    return False, ""


def is_tracked_player(name: str) -> bool:
    lowered = name.lower()
    return any(k in lowered for k in TRACKED_PLAYER_KEYS)


def matched_players(home: str, away: str) -> list[str]:
    found: list[str] = []
    combined = f"{home} {away}".lower()
    for full_name in TRACKED_PLAYERS:
        last_name = full_name.split()[-1].lower()
        first_initial = full_name.split()[0].lower()
        if last_name == "lebrun":
            if "félix" in combined or "felix" in combined or "f. lebrun" in combined:
                if "Félix Lebrun" not in found:
                    found.append("Félix Lebrun")
            if "alexis" in combined or "a. lebrun" in combined:
                if "Alexis Lebrun" not in found:
                    found.append("Alexis Lebrun")
        elif last_name in combined or first_initial in combined:
            if full_name not in found:
                found.append(full_name)
    return found


def slugify(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")[:48]


def fetch_wtt_events_and_matches(now_paris: datetime) -> tuple[list[dict], list[dict]]:
    """
    Attempts to fetch current/upcoming WTT events & matches from public WTT feeds.
    Returns (matches, tournaments). Fails gracefully with empty lists if unavailable.
    """
    matches: list[dict] = []
    tournaments: list[dict] = []

    # Public WTT events calendar feed
    events_url = "https://wttsimfiles.blob.core.windows.net/wtt-media/events/events_list.json"
    data = http_get_json(events_url, timeout=10)
    if not isinstance(data, list):
        return matches, tournaments

    window_start = now_paris - timedelta(days=3)
    window_end = now_paris + timedelta(days=21)

    active_events: list[dict] = []
    for ev in data:
        if not isinstance(ev, dict):
            continue
        start_raw = ev.get("StartDateTime") or ev.get("startDate") or ""
        end_raw = ev.get("EndDateTime") or ev.get("endDate") or start_raw
        try:
            ev_start = datetime.fromisoformat(str(start_raw).replace("Z", "+00:00")).astimezone(PARIS_TZ)
            ev_end = datetime.fromisoformat(str(end_raw).replace("Z", "+00:00")).astimezone(PARIS_TZ)
        except ValueError:
            continue
        if ev_end >= window_start and ev_start <= window_end:
            active_events.append(ev)

    for ev in active_events[:4]:
        ev_id = str(ev.get("EventId") or ev.get("id") or "")
        ev_name = str(ev.get("EventName") or ev.get("name") or "WTT Event").strip()
        city = str(ev.get("City") or "").strip()
        country = str(ev.get("Country") or "").strip()
        location = ", ".join(p for p in [city, country] if p)
        vpn_req, vpn_note = detect_vpn(f"{ev_name} {location}")

        if not ev_id:
            continue

        # Attempt to fetch match list for this event
        matches_url = f"https://wttsimfiles.blob.core.windows.net/wtt-media/events/{ev_id}/matches.json"
        m_data = http_get_json(matches_url, timeout=10)
        if not isinstance(m_data, list):
            continue

        rounds_map: dict[str, list[dict]] = {}
        for m in m_data:
            if not isinstance(m, dict):
                continue
            p1 = str(m.get("player1") or m.get("home") or "").strip()
            p2 = str(m.get("player2") or m.get("away") or "").strip()
            if not p1 or not p2:
                continue

            status_raw = str(m.get("status") or "scheduled").lower()
            status = (
                "live"
                if "live" in status_raw or "progress" in status_raw
                else "finished"
                if "finish" in status_raw or "complete" in status_raw
                else "scheduled"
            )
            score = str(m.get("score") or "").strip()
            round_name = str(m.get("round") or "Tableau").strip()
            start_iso = str(m.get("start_iso") or now_paris.isoformat())

            followed = matched_players(p1, p2)
            if followed:
                matches.append(
                    {
                        "id": f"wtt_{ev_id}_{slugify(p1)}_{slugify(p2)}",
                        "category": "INTL",
                        "competition": f"{ev_name} · {round_name}",
                        "home": p1,
                        "away": p2,
                        "start_iso": start_iso,
                        "status": status,
                        "score": score,
                        "followed": followed,
                        "broadcast": "YouTube WTT",
                        "url": YOUTUBE_WTT,
                        "vpn_required": vpn_req,
                        "vpn_note": vpn_note,
                    }
                )

            if round_name.lower() in {"qf", "quarterfinal", "quarts de finale", "sf", "semifinal", "demi-finales", "f", "final", "finale"}:
                s1, s2 = "", ""
                if "-" in score:
                    parts = [x.strip() for x in score.split("-", 1)]
                    s1, s2 = parts[0], parts[1]
                winner = 0
                if status == "finished" and s1.isdigit() and s2.isdigit():
                    winner = 1 if int(s1) > int(s2) else (2 if int(s2) > int(s1) else 0)
                rounds_map.setdefault(round_name, []).append(
                    {
                        "id": f"bm_{ev_id}_{slugify(p1)}_{slugify(p2)}",
                        "player1": p1,
                        "country1": str(m.get("country1") or ""),
                        "score1": s1,
                        "player2": p2,
                        "country2": str(m.get("country2") or ""),
                        "score2": s2,
                        "status": status,
                        "winner": winner,
                        "highlight1": is_tracked_player(p1),
                        "highlight2": is_tracked_player(p2),
                        "url": YOUTUBE_WTT,
                        "vpn_required": vpn_req,
                    }
                )

        if rounds_map:
            tournaments.append(
                {
                    "id": f"wtt_{ev_id}",
                    "name": ev_name,
                    "location": location,
                    "dates": "",
                    "category": "WTT · SIMPLE MESSIEURS",
                    "rounds": [{"name": k, "matches": v} for k, v in rounds_map.items()],
                }
            )

    return matches, tournaments


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


def write_status_index(site_dir: Path, updated_label: str, match_count: int, tournament_count: int) -> None:
    html = f"""<!doctype html>
<html lang="fr">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>PingPong Tracker — Feed JSON</title>
  <style>
    body {{ font-family: system-ui, -apple-system, sans-serif; background: #09090b; color: #fafafa; padding: 32px; max-width: 640px; margin: 0 auto; }}
    .card {{ background: #131316; border: 1px solid #27272a; border-radius: 12px; padding: 20px; }}
    a {{ color: #e11d48; text-decoration: none; font-weight: 600; }}
    code {{ background: #18181b; padding: 2px 6px; border-radius: 4px; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>PingPong Tracker Feed</h1>
    <p>Dernière mise à jour : <strong>{updated_label}</strong></p>
    <p>{match_count} matchs · {tournament_count} tableaux internationaux</p>
    <p><a href="./matches.json">Ouvrir matches.json →</a></p>
  </div>
</body>
</html>
"""
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

    wtt_matches, wtt_tournaments = fetch_wtt_events_and_matches(now_paris)
    manual_matches, manual_tournaments = load_manual_overrides(Path(args.manual))

    all_matches = merge_by_id(wtt_matches, manual_matches)
    all_tournaments = merge_by_id(wtt_tournaments, manual_tournaments)

    # Ensure VPN flags are auto-filled if omitted
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
        "tournaments": all_tournaments,
    }

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    write_status_index(out_path.parent, updated_label, len(all_matches), len(all_tournaments))
    print(f"[ok] Wrote {out_path} ({len(all_matches)} matches, {len(all_tournaments)} tournaments)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
