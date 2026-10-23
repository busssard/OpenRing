# Architecture

How Open Ring talks to the ring, stores the data and turns it into nights, days and scores.
For building and testing see [DEVELOPMENT.md](DEVELOPMENT.md).

## Data flow

```
ring ──Bluetooth──> GattLink ──> RingClient ──> Db.events (raw, then dated)
                                  auth, time sync,          │
                                  history drain             ▼
                 Analyzer: NightFinder ─> Stager ─> NightAnalyzer ─> Scores ─> Db.nights, Db.days ─> UI
```

Nothing leaves the phone: the app has no internet permission.

## Packages (`app/src/main/java/app/openring/`)

| Package | Responsibility |
| --- | --- |
| `core/` | Pure Java, no Android imports, unit tested on the JVM. `Protocol` (request bytes), `Packet` (framing), `Auth` (key handshake), `RingClient` (requests, history drain, live parsing), `HistoryBatch` and `ExtEnvelopeParser` (history batches), `EventCodec` (event decoders), `TimeLine` (ring counter to wall clock), `Tilt` (games), `SyncError` (plain sentences for failed syncs, pairing, Settings actions on the ring, live heart rate and game motion), `Texts` (a number with its word in singular or plural, the toast length limit, the sentence for a failed export), `Hex` |
| `core/analysis/` | `NightFinder` (where the sleeps are), `Stager` (sleep stages), `ScreenTime` (phone screen sessions, apps in front, their effect on a night), `ScreenScore` (screen score), `NightAnalyzer` (night metrics), `WakeUps` (the one definition of a wake up, a minute or more awake inside the night, used by the Sleep page, the timeline and the Settings preview; issue #70), `Scores` (Sleep, Readiness, Activity), `Wellness` (activity, calories, sleep debt, radar, VO2 max), `SleepPattern` (average night), `StageShare` (stage percentages that add up to 100), `LiveHrv`, `Stats`, `TS` (time series) |
| `data/` | `Db` (SQLite; real and demo files), `Analyzer` (runs analysis after each sync), `Json` (night and day JSON), `Prefs` (settings, encrypted ring key), `DataCheck`, `RightNow`, `SleepHistory`, `Backup`, `Demo`, `Exporter`, `ScreenUse` (copies Android's screen on and off log and which app was in front), `ScreenDays` (screen time per day for the Screen tab, with apps left out) |
| `ble/` | `GattLink` (connection, MTU, bonding, notifications), `RingScanner`, `RingManager` (sync, live, motion, pairing; one worker thread and one lock so only one task uses the ring) |
| `sync/` | `SyncService` (foreground sync), `SyncJob` (every 3 hours) |
| `ui/` | `MainActivity` (tabs, date, follows the newest day), `Pages` (content of every tab), `Charts` (hand drawn views; `Scrub` adds touch readout), `Ui` (colors, cards, buttons, inputs), `Explain` (texts), `DayData`, `SetupActivity`, `SettingsActivity`, `LiveActivity`, `LogActivity`, `Permissions`, `games/` |

## Talking to the ring

* GATT service `98ed0001-a541-11e4-b6a0-0002a5d5c51b`; requests are written to `98ed0002`, replies
  arrive on `98ed0003`. Ring 4 also offers notify channels `98ed0004` to `98ed0006` (subscribed and
  logged, purpose unknown). MTU must be at least 100.
* Framing: `tag | length | payload`, little endian. Extended operations sit under tag `0x2f`.
* Authentication: the app asks for a nonce (`2f 01 2b`), the ring replies with 15 bytes, and the app
  answers with the nonce padded to 16 bytes and encrypted with the 16 byte key (AES/ECB, `2f 11 2d`). Pairing a reset ring writes a new key (`24 10 <key>`).
  The key is stored encrypted with an Android Keystore key (`Prefs`).
* History: the ring keeps events (tag `0x41` and up) with a per boot decisecond counter. `RingClient`
  drains them in batches (extended `2f 0c 41`, or legacy GetEvent `0x10` on firmware that lacks it,
  like Ring 4 fw 2.6.7), with safeguards against replays, reboots and lost links. A cursor in `Db.kv`
  makes the next sync continue where the last stopped.
* Time: the counter becomes a date through time records: the ring's `time_sync` (`0x42`) and
  `rtc_beacon` (`0x85`) events plus a phone anchor written at the end of each drain. `Db.rebuildTime`
  maps every boot segment from scratch after each sync, which also repairs interrupted syncs. It works
  the result out in Java and writes only the rows whose segment or date changed (issue #21); the
  pipeline test keeps the old full SQL rewrite as the reference and checks every row against it.
* Measurements on the ring (daytime heart rate `0x02`, SpO2 `0x04`, resting heart rate `0x08`) have
  modes off, automatic, requested and live. A ring paired outside the official app starts with them off.

### Ring 4 firmware 2.6.7 (ORE_07) facts

* No live stream on `98ed0003`. Live heart rate, HRV and SpO2 are read by draining the ring's stored
  history every 4 seconds while the measurements are re armed every 30 seconds.
* SpO2 "live" mode is refused, "requested" works. "Feature latest" returns zeros.
* The ring falls back from live to automatic after about 48 seconds.
* Accelerometer streaming (games) is unconfirmed; if no motion frame arrives within the first 15 seconds,
  the game ends its ring session and falls back to touch steering.

## Storage (`Db`, SQLite)

| Table | Content |
| --- | --- |
| `events` | Every raw ring event: `tag`, `ds` (ring counter), `body`, `epoch`, `seg` (boot segment), `unix` (date once known) |
| `anchors` | Unused (kept from an early schema). Time records are `time_sync` and `rtc_beacon` rows in `events`; the phone anchor is stored as a `time_sync` row |
| `nights` | One row per sleep period, the full analysis as JSON (stages as a digit string per 30 seconds: 1 deep, 2 light, 3 REM, 4 awake, 0 unknown) |
| `days` | One row per day: scores, activity, vitals, trends as JSON |
| `screen` | Phone use sessions (start, end) from Android's usage log, which keeps only about a week (version 3). Since 1.20 use counts while the screen is on and the phone unlocked, the lock state taken only from keyguard shown and hidden; a phone without a lock screen logs neither and counts from screen on (`ScreenTime.sessions`, kv `screen_rule`) |
| `apps` | When each app was in front (package, start, end), from the same log (version 4). Apps the person leaves out (Prefs) are subtracted from screen time; the sleep analysis ignores this list |
| `zones` | The phone's time zone over time (`start`, `stop`, `id`; version 5, issue #22). Noted at every app start and analysis (not in demo mode): the same zone extends the newest row, another zone opens a new one; the first row starts at 0. `Db.zoneAt` gives a night its zone from its end; between two rows the later one counts. Kept by Re analyse all, deleted by Delete all local data |
| `battery`, `kv` | Battery history; small key value state (cursor, analysis progress, errors) |

Demo mode uses a separate file (`openring-demo.db`), so demo and real data never mix.

Restoring a full backup (`Backup.prepare`, then `finish`) first checks the backup's database on its own
(`Db.inspect`: `quick_check`, a version this app can open, the Open Ring tables) and refuses it whole if
it fails. If the phone's history reaches more than an hour later or holds more events, Setup asks
before replacing it (#26). `Db.replaceWith` keeps the old file as `openring.db.old` until the new one
opens with the event count the check saw, and puts it back if not (#27). A left over `.old` means a
swap was cut off, so `Db.get` puts it back before opening the real file. Each restore has its own temp
file in the cache. Histories are never merged.

## Analysis

1. `NightFinder` finds sleep periods from the ring's bedtime markers, its per 30 second sleep state and
   nocturnal events. A period is never stretched into time the ring marks as awake at its edges. A wake
   up in the middle of the night (up to 3 hours, the gap from 22:00 at the earliest to before 06:30, the
   sleep before it begun at 21:00 or later, the ring worn through it) joins the pieces before and after
   into one night; the time the ring calls awake in the gap counts as awake, and ring stages of both
   pieces keep their own times (issue #42). kv `night_finder` holds the rules version. `Analyzer` then
   drops periods that read room temperature or an implausible heart rate (ring lying on a table).
   When "Count phone use as awake" is on (default), phone use of a minute or more that overlaps the first or
   last 10 minutes of a period moves its edge past it, and a period of under 15 minutes left is dropped.
2. `Stager` estimates stages per 30 seconds from heart rate, HRV and movement (open_health's model free
   stager). Ring 4 does not stage sleep itself. Sleep versus wake is reliable; deep and REM are estimates.
   Every 30 seconds with the phone screen on for at least 15 seconds becomes awake (`ScreenTime.markAwake`).
   A single use of 15 seconds or more that a step border splits so neither part reaches 15 seconds marks
   the step holding most of it (issue #41); `ScreenTime.AWAKE_RULE` (kv `phone_awake`) re analyses nights on a change.
3. `NightAnalyzer` computes durations, efficiency, latency, wake ups, cycles, lowest heart rate, HRV,
   breathing, SpO2 and nightly skin temperature.
4. `Scores` combines contributors with the weights open_health recovered from Oura's engine (Sleep
   35/15/10/10/10/10/10); contributor curves are transparent and based on sleep research. Baselines become
   personal after about 14 nights.
5. Time zones (issue #22): each night takes its zone from `zones` and stores it as `zone`; its day, sleep timing
   and clock change marker use it, and a day uses its main night's zone. Screens format each night's times
   with its own zone (`Json.zone`, falling back to the phone's zone for nights without one), so a trip does
   not move earlier nights. A new zone row analyses the nights since the row before again.
6. `Analyzer` writes nights and days; it re analyses the last two days after each sync and everything
   when dating changed or the user asks (Settings, Help). "Everything" is the whole stored history with no
   age limit (a week at a time, so memory stays flat); with years of history that takes a few minutes.
7. The day's heart rate (Body tab, `core/analysis/DayHeart`, issue #72) is one line of quarter hour averages of
   every reading from midnight to midnight in the day's zone (one point per quarter hour, 92 to 100 on a clock
   change day), stored as `hr_day`. Lowest, Average and Highest are that line's lowest point, mean and highest
   point; the Body tab works them out from the stored line (they are not stored apart), so days analysed before
   show matching numbers without a re analysis. On a clock change day the chart's middle label stays at noon, and
   times in the hour that happens twice when the clocks go back say "(first time)" or "(second time)"
   (`ClockChange.repeatNote`, issue #97), in every touch readout too (the sleep stages timeline uses
   `ClockChange.stageTip`: the touched time gets the note, an end of the stretch only when its pass differs). The night's lowest heart rate (5 minute averages inside the sleep) stays a separate number.
   Heart rate from every ring source (HRV, always on HR, sleep period average) counts only from 30 to 220 bpm
   (`EventCodec.plausibleHr`, also for Today's heart rate and the ring finder; the night's own numbers keep 30 to 140).
   The day reads events from 30 minutes before midnight, so the values after midnight of an HRV event begun before it
   (six values, start + 0 to 25 minutes) count for the new day. kv `day_heart` holds the rules version
   (`DayHeart.VERSION`); a change analyses everything again once.

## Threading

`RingManager` runs every ring task on one worker thread and guards the ring with one lock (`op`).
Anything that holds the ring (live, games) must release it when its screen pauses. Sync waits up to
15 seconds for the lock, live 10 seconds and games up to 3 minutes; Settings actions on the ring
(read or switch measurements, factory reset) fail at once with "The ring is busy". A sync releases the
lock as soon as its download ends, before it reads the screen log and analyses (issue #21); `busy()`
stays true until that analysis is done, so screens reload only then, and the status keeps saying
"Analysing" (other ring tasks then only write to the log). Code that replaces or wipes the database
(restore, pairing a different ring) first waits for a running analysis (`synchronized
(Analyzer.class)`), except "Delete all local data" in Settings, which runs on the screen thread. Analysis and database reads in the UI run in AsyncTasks.

## Ring finder (issue #74)

`ui/FinderActivity` (Today under the score rings, Settings, Ring) never connects to the ring, so a sync
can run meanwhile. Everything below stops in `onPause`.

* Where was it last: `RightNow.lastWorn` reads 7 days of skin temperature and heart rate back from the
  newest stored temperature reading (not from now, so an old sync still has its history), dating heart
  rates like `Analyzer` (HRV value i at start + i × 300 s, AOHR at + i × 1.92 s). `core/analysis/LastWorn`
  finds when the ring came off (below 27 °C to the end, no heart rate more than 5 minutes after the first
  cool reading). Warmth alone is not trusted (issue #94): heart rates are split into night (HRV events)
  and daytime (green IBI, AOHR). The heart rate rule applies only when the ring stores daytime heart rate
  (`hrRule`: at least 12 warm readings outside sleep in the 48 hours before the newest reading, up to the
  newest daytime heart rate, 70 % of them with one within 30 minutes); otherwise temperature decides, as before. A warm stretch with a heart rate
  within 30 minutes of any of its readings is a finger as a whole (heart rate may stop while the hand
  moves); one without counts only when `hrRule` is off. A heart rate needs one from another stored event within 30 minutes
  (the values of one HRV event or AOHR burst never vouch for each other, so `lastWorn` passes an event
  number per heart rate); a lone one counts only next to a finger reading or after a warm newest reading (else it is a stray).
  Uncounted warm readings neither end nor start a cool stretch. At the end: warm with heart rate in the
  last hour (only such a heart rate confirms it on the card), or warm for under an hour with no heart rate yet (just put on), is worn; warm with heart rate
  at first but none for over an hour is `UNSURE` (charger, duvet) at the last heart rate; warm for over an
  hour without any heart rate near is ignored and the cool readings before decide. A charger right after
  taking it off (no cool reading between) reads as worn until it leaves the charger. The card text (`LastWorn.text`, the app passes its time format) lists what
  was left out (warm readings, a stray heart rate) and how old the newest reading is. `Prefs.lastHeard` is saved when a connection opens or closes
  and when any scan hears the saved address; `Prefs` keeps the battery read before the newest one.
* Hot and cold: `RingScanner.watch` runs one `SCAN_MODE_LOW_LATENCY` scan (Android blocks the sixth
  start within 30 s, so `RingScanner` counts the starts of `find` and `watch` together and spaces them). `core/analysis/Proximity` smooths the signal (median of 5,
  then 35 % follow), bands at -84, -72 and -60 dBm with 4 dB hysteresis, and the click interval. While
  the ring is connected (this app or another) it does not advertise; the screen says so. Readings outside
  -120 to -1 dBm (`Proximity.plausible`; 127 is the controller's "not available") are dropped in
  `FinderActivity.heard` and again in `Proximity.add` and `SearchWalk.rssi`, and do not count as heard
  (issue #95). After a scan failure `core/analysis/ScanRetry` holds the 30 s wait; Bluetooth off or a
  missing permission ends it, so the screen shows the real reason and starts again (issue #96).
* The screen is locked to portrait (the walk assumes an upright phone, and turning on the spot would
  otherwise rotate the screen and lose the walk) and handles size changes itself (issue #96).
* Turn on the spot: `core/analysis/SearchWalk` records every reading with the heading from
  `GAME_ROTATION_VECTOR` (phone upright against the chest) and fits one wave over 12 directions for the
  strongest one; a direction faced without hearing the ring counts as 6 dB below the weakest one heard.
* Search walk (phase 2): `core/analysis/StepDetector` counts steps from the accelerometer (registered
  only while recording; no activity recognition permission; a second bump within 250 ms is the same
  footfall), each step moves the person 0.41 × height along the last upright heading (relative to the
  first upright heading after Start, since the sensor's zero is arbitrary; a step that turned more than
  25° only moves if the next one goes straight on, so shuffling while turning stays a turn), and every
  reading keeps its place. Once the walk spans 2 m in two
  directions (at least 1 m wide, 6 s of readings), `SearchWalk.locate` fits signal = level −
  10 n log10(distance) − body shadow over a 0.2 m grid (walk plus 2 m), n 2 to 3.5, and gives a
  likelihood weighted centre and radius (1.8 × spread, at least 1 m; none above 3 m, at the grid's edge
  or when the model explains under 15 % of the signal's changes). `ui/MiniMap` draws it; under 1 m
  from the start the turn dial stays. The fit runs on its own thread on `SearchWalk.snapshot()` (at most 240 points). `DemoWalk` is the made up walk for demo mode.

## Known limits

* Scores are close to Oura's but not identical (Oura's tables and models are not public).
* Steps are estimated from activity intensity.
* Not ported: workout detection, cycle insights, cardiovascular age, firmware updates.

## Credits

Protocol, decoders and algorithms are ported from [open_oura](https://github.com/Th0rgal/open_oura) and
[open_health](https://github.com/Th0rgal/open_health) (MIT, Thomas Marchand), cross checked with the
[ringverse Ring 4 notes](https://github.com/ringverse/protocol/blob/main/oura/BLE.md),
[OpenStrap](https://github.com/OpenStrap/protocol), [BrightOura](https://github.com/gi-os/BrightOura)
and the Gadgetbridge Ring 4 work.
