# Lyrics source investigation (Phase 8)

**Date:** 2026-09-29  
**Status:** Complete. **No lyrics provider ships in this phase.**  
**Roadmap:** `docs/plans/player-experience-roadmap.md` Phase 8.

## Decision

No third-party lyric source meets the Phase 8 bar of *clear terms* for availability, attribution, caching, API stability, and offline use in a personal multi-provider player (local, Spotify, YouTube).

Phase 8 therefore stops after this document. That is an accepted outcome in the roadmap (“The investigation can conclude with no lyrics integration”).

Do not wire LRCLIB, lyrics.ovh, Genius scrape paths, Spotify private `color-lyrics`, NetEase-style scrapers, or similar into Kainos until a source with publisher-clear rights (or an explicit commercial license) is chosen later.

## What “acceptable” meant here

| Criterion | Requirement for Kainos |
|---|---|
| Availability | Resolve by track identity (artist/title/album/duration and/or provider ids); plain and/or timed lyrics; honest no-match / instrumental |
| Attribution | Must be able to show whatever credit the license demands |
| Caching rights | Disk cache for hearted / offline listening must be allowed or explicitly unnecessary |
| API stability | Documented public API, not unofficial scrapes or private player endpoints |
| Offline terms | Offline display must not violate the source’s rules |
| Clear rights | The *lyric text* must be licensed or otherwise cleared for display. A free HTTP API is not enough if the corpus is unlicensed fan content |

Kainos is personal-use, not a commercial product, but still redistributes lyric text on device screens. Unlicensed community corpora create real publisher risk even for private apps.

## Sources reviewed

### 1. LRCLIB (`https://lrclib.net`)

**Fit technically:** Strong. Public JSON API, no key, synced LRC + plain text, `instrumental` flag, duration matching (±2s), documented `429` + `Retry-After`, client identification via `User-Agent` / `X-User-Agent` / `Lrclib-Client`. Live probe on 2026-09-29 returned timed/plain Karma Police data successfully.

**Terms:** Server software is MIT (`tranxuanthang/lrclib` LICENSE). That license covers the *code*, not the lyric works in the database. Docs invite third-party clients and ask for responsible rate limiting. There is no publisher license statement, no offline/cache grant, and no attribution mandate from the operator.

**Open question left unanswered:** GitHub issue [#111](https://github.com/tranxuanthang/lrclib/issues/111) (2026-07, still open) asks whether commercial use and local caching are cleared and how rights are handled. The maintainer has not replied with a license for the lyric content. Community replies warn about publisher suits. DB dumps exist (`/db-dumps`), which increases redistribution surface but does not create rights.

**Verdict:** Technically attractive, **rights unclear**. Fails “clear terms.” Do not ship.

### 2. lyrics.ovh

**Fit technically:** Plain lyrics only (`GET /v1/{artist}/{title}`). No synced LRC. No auth.

**Terms:** Project code is MIT. README states lyrics are fetched by scraping Genius, AZLyrics, Paroles.net, LyricsMania, Letras.mus.br, Lyrics.com. No publisher clearance. Scraping those sites typically violates their ToS. No caching/offline policy, no attribution contract that clears the underlying works.

**Verdict:** Unlicensed scrape aggregator. Reject.

### 3. Genius API

**Fit technically:** Official API is built for annotations, songs, artists, referents. It does **not** return full lyric text for redisplay. Libraries that “get lyrics” scrape the public HTML song page.

**Terms:** Commercial API use needs a separate license (`api-sales@genius.com`). Genius treats site lyrics as licensed property; scraping violates ToS (widely documented, including by LyricsGenius maintainers). Attribution on scraped text does not create a license.

**Verdict:** Official path does not supply display lyrics; scrape path is ToS-violating. Reject.

### 4. Musixmatch Pro API

**Fit technically:** Licensed catalog, static and (on higher tiers) time-synced lyrics. Stable commercial API.

**Terms (as of investigation):**
- Default API Terms (https://about.musixmatch.com/apiterms, last updated 24 June 2025): non-commercial unless written approval; mandatory “powered by Musixmatch” credit each time data is used; no bulk harvest outside the API; on termination, delete all Musixmatch Data.
- Pricing (https://about.musixmatch.com/api-pricing): Basic $49/mo (static only, 500 lyrics calls/day), Grow $199/mo (adds sync), Scale $499/mo. **Lyrics caching is not included** on Basic / Grow / Scale. Caching is Enterprise-only (from ~$2,000/mo). FAQ states Basic/Scale-class plans forbid storing lyrics locally; real-time fetch only; audits and suspension for violations.

**Mismatch with Kainos:** Offline / hearted listening wants local cache. Affordable tiers forbid that. Paid non-Enterprise still needs continuous network and brand chrome. Personal-use non-commercial clause may help for a private app, but cache + cost still block a sensible ship.

**Verdict:** Rights are clear, product is real, **terms do not fit** this app without an Enterprise (or custom) deal. Do not ship on Basic/Grow/Scale.

### 5. LyricFind

**Fit technically:** Publisher-licensed display and sync used by large commercial services.

**Terms:** Custom commercial contracts (per-display / per-user / revenue share). No free public self-serve tier suitable for a personal Kotlin Multiplatform player. Caching and attribution follow the signed deal.

**Verdict:** Acceptable only after a paid license. Out of scope for Phase 8. Do not ship without a contract.

### 6. Spotify (official and unofficial)

**Official Web API:** No public lyrics endpoint. Lyrics in Spotify’s own clients come from a licensing partner, not from the documented developer API.

**Unofficial:** Internal `color-lyrics` / `sp_dc` cookie flows (and third-party wrappers around them) violate Spotify Developer Terms, are account-tied, and break when auth or endpoints change.

**Verdict:** Reject all unofficial Spotify lyric paths. No official third-party lyrics API to integrate.

### 7. Other scrapers / mirrors

NetEase-style, AZLyrics direct scrape, random “Spotify Lyrics API” proxies, and similar: no publisher grant, unstable, often ToS-violating. Reject.

### 8. Local sidecar / embedded lyrics (user-supplied)

**Examples:** `.lrc` / `.txt` next to a local file; ID3 `USLT` / `SYLT`; Vorbis `LYRICS` / `UNSYNCEDLYRICS`.

**Terms:** Clear for *display of content the user already placed on disk*. Kainos would not be redistributing a third-party corpus; it would read the user’s own files. Offline is native. Attribution is N/A beyond optional “from file” labeling.

**Limits:** Only helps local (and maybe imported) tracks. Does not cover Spotify Connect / librespot or YouTube streams. Coverage depends on whether the user’s library already has lyrics tags or sidecars. Not a full Phase 8 Now Playing feature across providers.

**Verdict:** Rights-clear as a *narrow future enhancement*, not enough to claim Phase 8 lyrics for the whole app. Not implemented in this phase.

## Cross-check against Phase 8 acceptance

| Acceptance item | Online free/community APIs | Musixmatch Basic–Scale | Local sidecar only |
|---|---|---|---|
| Identify source where required | Possible | Required brand credit | Optional “local file” |
| Timing follows seek | LRCLIB yes; lyrics.ovh no | Grow+ | If LRC/SYLT present |
| Fetch separate from audio | Yes | Yes | N/A |
| No guessed lyrics | Implementable | Implementable | Implementable |
| Cache follows source terms | **Unclear or forbidden** | **Forbidden** | OK |
| Same on Android and desktop | Implementable | Implementable | Implementable for local |

Nothing in the “clear online rights + offline/cache OK” column is available without a paid Enterprise/custom license.

## Conclusion

1. **Do not ship a lyrics provider or Now Playing lyrics UI in Phase 8.**
2. **Reason:** Every free/community API either scrapes, lacks publisher clearance, or (LRCLIB) leaves lyric copyright unanswered. Licensed APIs that are clear either forbid caching on affordable tiers or require a commercial contract Kainos does not have.
3. **Success condition met:** Investigation documented; no source chosen prematurely.
4. **If lyrics return later:** Prefer a publisher-licensed API with an explicit cache/offline clause (Musixmatch Enterprise, LyricFind, or equivalent), or a local-only reader for user sidecars/tags. Keep fetch off the playback path, identity-keyed, and honest about no-match / instrumental / rate-limit / offline.

## Sources consulted

- LRCLIB docs SPA / API behavior (`https://lrclib.net/docs`, live `GET /api/get`, 2026-09-29)
- `https://github.com/tranxuanthang/lrclib` README + MIT LICENSE
- `https://github.com/tranxuanthang/lrclib/issues/111`
- `https://github.com/NTag/lyrics.ovh` README (scrape source list)
- Genius API docs notes (commercial license required; no raw lyric payload)
- Musixmatch API Terms (`https://about.musixmatch.com/apiterms`) and Pro API pricing (`https://about.musixmatch.com/api-pricing`)
- LyricFind public licensing overview / consumer site terms
- Spotify Web API reference gap + public documentation of private lyrics endpoints as ToS-risk
