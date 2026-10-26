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
| `core/` | Pure Java, no Android imports, unit tested on the JVM. `Protocol` (request bytes), `Packet` (framing), `Auth` (key handshake), `RingClient` (requests, history drain, live parsing), `HistoryBatch` and `ExtEnvelopeParser` (history batches), `EventCodec` (event decoders), `TimeLine` (ring counter to wall clock), `Tilt` (games), `SyncError` (plain sentences for failed syncs, pairing, Settings actions on the ring, live heart rate and game motion), `Texts` (a number with its word in singular or plural, the toast length limit, the sentence for a failed export), `RingTasks` (which sync a stop reaches, when Live rejoins a session, which sync shows the final status line; issues #43 and #153), `Hex` |
| `core/analysis/` | `NightFinder` (where the sleeps are), `Stager` (sleep stages), `ScreenTime` (phone screen sessions, apps in front, their effect on a night), `ScreenScore` (screen score), `NightAnalyzer` (night metrics), `WakeUps` (the one definition of a wake up, a minute or more awake inside the night, used by the Sleep page, the timeline and the Settings preview; issue #70), `Scores` (Sleep, Readiness, Activity), `Wellness` (activity, calories, sleep debt, radar, VO2 max), `SleepPattern` (average night), `StageShare` (stage percentages that add up to 100), `LiveFeed` (sorts a live refresh: real beats apart from always on heart rate, issue #152), `LiveHrv`, `Stats`, `TS` (time series) |
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
  history every 4 seconds while the measurements are re armed every 30 seconds. Live HRV comes only from
  real beat to beat intervals (clean green IBI beats, or a live indication's interval); always on heart rate
  values are averages over a couple of seconds, so they give the heart rate number and chart only, and with
  them alone the HRV tile says why it has no value (`LiveFeed`, `LiveHrv.note`, issue #152).
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
| `screen_reads` | The time ranges of Android's usage log really read (`start`, `stop`; version 6, issue #131), merged when they touch. A read asked to start more than 7 days back counts only from the oldest event Android returned; a day the reads do not cover (a gap, after the last read, or a past day read only part way) has no screen time and no score instead of 0; only today counts up to the last read. The upgrade stores one range from the oldest to the newest stored session, so older days stay as they were; the demo stores one range for its five weeks |
| `apps` | When each app was in front (package, start, end), from the same log (version 4). Apps the person leaves out (Prefs) are subtracted from screen time; the sleep analysis ignores this list |
| `zones` | The phone's time zone over time (`start`, `stop`, `id`; version 5, issue #22). Noted at every app start and analysis (not in demo mode): the same zone extends the newest row, another zone opens a new one; the first row starts at 0. `Db.zoneAt` gives a night its zone from its end; between two rows the later one counts. Kept by Re analyse all, deleted by Delete all local data |
| `battery`, `kv` | Battery history; small key value state (cursor, analysis progress, errors) |

Demo mode uses a separate file (`openring-demo.db`), so demo and real data never mix.

Restoring a full backup (`Backup.prepare`, then `finish`) first checks the backup's database on its own
(`Db.inspect`: `quick_check`, a version this app can open, and every table and column the app has for that
version, `Db.schemaProblem`, issue #156) and refuses it whole if it fails. If the phone's history reaches more than an hour later or holds more events, Setup asks
before replacing it (#26). `Db.replaceWith` keeps the old file as `openring.db.old` until the new one
opens with the event count the check saw, and puts it back if not (#27). A left over `.old` means a
swap was cut off, so `Db.get` puts it back before opening the real file; the opened, upgraded file must
also pass the schema check of the current version. Each restore has its own temp file in the cache.
Histories are never merged. A backup holds every setting; restored numbers are kept in the ranges Settings
allows, and the stage detail level comes along only with the history or onto a phone without nights (#33).

Export database and a full backup never copy the live file (issue #115). `Exporter.snapshot` waits for a
running analysis, then makes a copy in the cache: `VACUUM INTO` on SQLite 3.27 and later (Android 11 and
newer), otherwise the file and its write ahead log copied while the write lock is held (a sync waits those
seconds). The copy is made a single file and must pass `Db.inspect` and hold at least the events the
history had, before anything is written to the file the person picked; otherwise `Exporter.NotSaved`.

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
   The stage detail level (`StageFilter`) then folds short pieces, and phone use and the wake ups the ring
   reports itself are marked awake again (`NightAnalyzer.detailStages`). The Settings preview runs the same
   function on the stored `stages_raw` (`Analyzer.preview`, phone use chosen by `ScreenTime.forNight` as in the
   analysis, the ring's wake ups from the night's `ring_awake`, stored since issue #30), so it shows the night
   that saving produces.
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
   age limit (a week at a time, so memory stays flat); with years of history that takes a few minutes. Each day summary reads the nights and summaries of up to 104 days before it for its baselines; during one analysis each of those is read and parsed once and kept as the few fields the summaries use (`Analyzer.History`, issue #139: before, the same night was parsed about 200 times, most of a full run). Nothing about it is stored, the results are byte for byte the same (pipeline test), and what is older than the 104 days is dropped as the days move on.
   Each week replaces the stored nights from its start, moved back to the start of any sleep (found now or
   stored) that crosses it, so a sleep stored with an end shortened by phone use is replaced, never doubled
   (`Analyzer.boundary`, issue #132); the days of the nights it replaced are analysed again too, and the main
   sleep of such a day is chosen also against its stored sleeps that stay (their flag is updated). A day the analysis
   visits that has no data left (no activity, heart rate or sleep, for example after its events were dated
   again) loses its stored summary in the same step, and stored days after the newest dated event are visited
   for that too (issue #58). Not while an undated event (other than a time record) follows the newest dated one in row
   order (its boot has no usable time record yet): then only days that end by the newest dated event can go, and the log says the others
   were kept.
7. The day's heart rate (Body tab, `core/analysis/DayHeart`, issue #72) is one line of quarter hour averages of
   every reading from midnight to midnight in the day's zone (one point per quarter hour, 92 to 100 on a clock
   change day), stored as `hr_day`. Lowest, Average and Highest are that line's lowest point, mean and highest
   point; the Body tab works them out from the stored line (they are not stored apart), so days analysed before
   show matching numbers without a re analysis. On a clock change day the chart's middle label stays at noon, and
   times in the hour that happens twice when the clocks go back say "(first time)" or "(second time)"
   (`ClockChange.repeatNote`, issue #97), in every touch readout too (the sleep stages timeline uses
   `ClockChange.stageTip`: the touched time gets the note, an end of the stretch only when its pass differs). The night's lowest heart rate (5 minute averages inside the sleep) stays a separate number.
   Heart rate from every ring source (HRV, always on HR, green IBI beats, sleep period average) counts only from 30 to
   220 bpm (`EventCodec.plausibleHr`; a beat interval from 273 to 2000 ms, `EventCodec.plausibleIbi`, issue #148), also
   for Today's heart rate, the ring finder and the live screen; the night's own numbers keep 30 to 140.
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
"Analysing" (other ring tasks then only write to the log). A sync counts for `busy()` from its start,
also while it waits for the lock, and Settings "Sync now" refuses while `busy()` like the main screen; when
two syncs overlap anyway, only the last one to end sets the final line ("Up to date" or its error), so the
first one's line cannot cover a download still under way (`RingTasks.syncEndStatus`, issue #43).

Stopping a sync reaches only that sync: the background job holds a `SyncTicket`, and `cancelSync` stops it
only while its download holds the ring (`RingTasks.Owner`, one monitor for the check and the stop). Once
the download is over, Android stopping the job leaves Live, a game or the person's own sync alone (#43).

Live (issue #153): `liveSession` is the token of the newest live session queued or running. `startLive`
rejoins only when that session is still the current one (`RingTasks.rejoinLive`); a session that was
stopped (turned phone, Done and back) and is still cleaning up gets a new session queued behind it on the
worker, which waits for the lock as usual. Messages of an ended session only go to the log.

Code that replaces or wipes the database first waits for a running analysis (`synchronized
(Analyzer.class)`): restore, pairing a different ring, and "Delete all local data" (`Analyzer.deleteAll`,
one transaction). Delete also runs inside `RingManager.whileRingFree`, which holds the ring lock and
refuses while a sync is under way, so no download or analysis writes nights or events back after it
(issue #43). Analysis and database reads in the UI run in AsyncTasks.
A task that must outlive a turned phone (screens are rebuilt, no `configChanges`) holds only the
application context and reports through a holder to the screen attached now: `DemoRun` in
`MainActivity` (#29) and the repair in Data check (`LogActivity.REPAIR`, a `core/OneRun`, #130).

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
  newest daytime heart rate, 70 % of them with a paired daytime heart rate within 30 minutes, issue #124:
  a single value every 35 to 45 minutes pairs with nothing, so it switches the rule off instead of making
  every warm stretch fail); otherwise temperature decides, as before. A warm stretch with a paired heart rate
  within 30 minutes of any of its readings, or with lone heart rates of two different events at most an hour
  apart within 30 minutes of it and not past its neighbouring cool readings (`loneEvents`, sparse daytime heart rate after a dense day), is a finger as a
  whole (heart rate may stop while the hand moves); one without counts only when `hrRule` is off. A heart rate needs one from another stored event within 30 minutes
  (the values of one HRV event or AOHR burst never vouch for each other, so `lastWorn` passes an event
  number per heart rate, and pairing is worked out first because the rule uses it); a lone one counts only next to a finger reading or after a warm newest reading (else it is a stray).
  Uncounted warm readings neither end nor start a cool stretch. At the end: warm with heart rate in the
  last hour (only such a heart rate confirms it on the card), or warm for under an hour with no heart rate yet (just put on), is worn; warm with heart rate
  at first but none for over an hour is `UNSURE` (charger, duvet) at the last heart rate; warm for over an
  hour without any heart rate near is ignored and the cool readings before decide. A charger right after
  taking it off (no cool reading between) reads as worn until it leaves the charger. The card text (`LastWorn.text`, the app passes its time format) lists what
  was left out (warm readings, a stray heart rate, named once per event: `strayInWarm`) and how old the newest reading is. `Prefs.lastHeard` is saved when a connection opens or closes
  and when any scan hears the saved address; `Prefs` keeps the battery read before the newest one.
* Hot and cold: `RingScanner.watch` runs one `SCAN_MODE_LOW_LATENCY` scan (Android blocks the sixth
  start within 30 s, so `RingScanner` counts the starts of `find` and `watch` together and spaces them). `core/analysis/Proximity` smooths the signal (median of 5,
  then 35 % follow), bands at -84, -72 and -60 dBm with 4 dB hysteresis, and the click interval. While
  the ring is connected (this app or another) it does not advertise; the screen says so. Readings outside
  -120 to -1 dBm (`Proximity.plausible`; 127 is the controller's "not available") are dropped in
  `FinderActivity.heard` and again in `Proximity.add` and `SearchWalk.rssi`, and do not count as heard
  (issue #95); while only such values arrive the label reads "No usable signal" (`Proximity.label(band, unusable)`, issue #125). After a scan failure `core/analysis/ScanRetry` holds the 30 s wait; Bluetooth off or a
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
