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
| `core/` | Pure Java, no Android imports, unit tested on the JVM. `Protocol` (request bytes), `Packet` (framing), `Auth` (key handshake), `RingClient` (requests, history drain, live parsing), `HistoryBatch` and `ExtEnvelopeParser` (history batches), `EventCodec` (event decoders), `TimeLine` (ring counter to wall clock), `Tilt` (games), `SyncError` (plain sentences for failed syncs, pairing, Settings actions on the ring, live heart rate and game motion; which failed sync attempts are tried again, only Bluetooth off, no ring found or a stop end a sync at once, issue #157; a failed sync always stores an error text, issue #91), `Texts` (a number with its word in singular or plural, the toast length limit, the sentence for a failed export), `RingTasks` (which sync a stop reaches, when Live rejoins a session, which sync shows the final status line, that demo mode never uses the ring; issues #43, #153 and #185), `LiveSessions` (the live session tokens: start or rejoin, stop, a queued session that became stale, a session that ends clears only its own mark; issue #153), `LiveScreen` (Live measures only while in front, and in demo mode only made up readings; issues #185 and #187), `DemoLive` (the made up Live readings of demo mode), `FingerCheck` (Find my ring: when it asks the ring whether it is on a finger, its strip line and chip, the 30 s blink; issue #192), `PermissionAsk` (whether Android still shows the permission dialog), `BackgroundJob` (whether the background sync job is scheduled, kept or cancelled, issue #193), `Pairing` (the order of the pairing steps so a key written to a ring is never lost, and the hand over of the result to the Setup screen shown now, issue #218), `RingGate` (the ring lock and the count of syncs under way, shared with the code that swaps the database file, issue #219), `Hex` |
| `core/analysis/` | `NightFinder` (where the sleeps are), `Stager` (sleep stages), `ScreenTime` (phone screen sessions, apps in front, their effect on a night), `ScreenScore` (screen score), `NightAnalyzer` (night metrics), `WakeUps` (the one definition of a wake up, a minute or more awake inside the night, used by the Sleep page, the timeline and the Settings preview; issue #70), `Scores` (Sleep, Readiness, Activity), `Wellness` (activity, calories, sleep debt, radar, VO2 max), `SleepPattern` (average night), `StageShare` (stage percentages that add up to 100), `LiveFeed` (sorts a live refresh: real beats apart from always on heart rate, issue #152), `FinderLayout` (how Find my ring shares the screen's height, issue #191), `FingerNow` (on a finger now, from what the finder just downloaded, issue #192), `LiveHrv`, `Stats`, `TS` (time series) |
| `data/` | `Db` (SQLite; real and demo files), `Analyzer` (runs analysis after each sync), `Json` (night and day JSON), `Prefs` (settings, encrypted ring key), `DataCheck`, `RightNow`, `SleepHistory`, `Backup`, `Demo`, `Exporter`, `ScreenUse` (copies Android's screen on and off log and which app was in front), `ScreenDays` (screen time per day for the Screen tab, with apps left out) |
| `ble/` | `GattLink` (connection, MTU, bonding, notifications), `RingScanner`, `RingManager` (sync, live, motion, pairing; one worker thread and one lock so only one task uses the ring) |
| `sync/` | `SyncService` (foreground sync), `SyncJob` (every 3 hours; scheduled only when no equal job is pending, since replacing a job stops it while it runs, `core/BackgroundJob`, issue #193) |
| `ui/` | `MainActivity` (tabs, date, follows the newest day), `Pages` (content of every tab), `Charts` (hand drawn views; `Scrub` adds touch readout), `Ui` (colors, cards, buttons, inputs), `Explain` (texts), `DayData`, `SetupActivity`, `SettingsActivity`, `LiveActivity`, `LogActivity`, `Permissions`, `games/` |

## Talking to the ring

* GATT service `98ed0001-a541-11e4-b6a0-0002a5d5c51b`; requests are written to `98ed0002`, replies
  arrive on `98ed0003`. Ring 4 also offers notify channels `98ed0004` to `98ed0006` (subscribed and
  logged, purpose unknown). MTU must be at least 100.
* Framing: `tag | length | payload`, little endian. Extended operations sit under tag `0x2f`.
* Authentication: the app asks for a nonce (`2f 01 2b`), the ring replies with 15 bytes, and the app
  answers with the nonce padded to 16 bytes and encrypted with the 16 byte key (AES/ECB, `2f 11 2d`). Pairing a reset ring writes a new key (`24 10 <key>`).
  The key is stored encrypted with an Android Keystore key (`Prefs`).
  Pairing (`core/Pairing`, issue #218) stores a key on the phone (the kept key, `Prefs.keepPendingKey`, written with
  `commit`, with the serial of the ring it goes to) before it writes it to the ring, and nothing goes to the ring when that
  fails. A kept key is never overwritten: "Pair and create a new key" uses it again (also on another ring, which then shares
  it), and a typed key with "Install this key" is not kept while another key is (it is the person's own). A ring that
  answers "already has a key" is tried with the key written and then with the kept key (after an interrupted try it holds
  exactly the kept one); only the ring the kept key was meant for (same serial) can drop it, by answering "wrong key" to it,
  or by being paired. A clear refusal (another status) keeps it. Once the ring accepts a key, one `commit` saves key,
  address, serial, `setup_done` and the note `pair_unseen`, and removes the kept key when it was meant for this ring
  (`Prefs.pairDone`); firmware, measurements and clock after that are best effort. A different ring's history is reset
  just before that commit (a limit: if that commit then fails, the history is gone while the old key stays).
  Setup shows the kept key in a card ("Key from your last try", with the ring's serial), Settings, Key and backup too. While `pair_unseen` is set the main screen opens Setup on the key page; leaving
  that page (Start syncing, Copy key, Back) clears it, and so does clearing the key.
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

Demo mode uses a separate file (`openring-demo.db`), so demo and real data never mix. Demo mode never uses the ring
(issue #185): `RingManager.open`, the start of every connection, refuses while demo mode is on or the demo file is open
(`RingTasks.demoRefusal`, raw text `SyncError.DEMO_RING`), and `drainNew` takes its database through the same check, so
even a session opened before demo mode was turned on cannot store into the demo file. Live and the games check it before
they queue anything; Live in demo mode shows made up readings from `core/DemoLive` instead, and the games steer by touch.

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
   pieces keep their own times (issue #42). These clock times are read in the zone the sleep was slept in
   (`Analyzer.sleptIn`, see Time zones below), decided once per night from the end of the last piece reached
   through gaps of up to 3 hours (`NightFinder.nightEnds`), never in the phone's zone now, so a trip does not split nights
   analysed again later (issue #161). A period keeps where the ring's own hypnogram ends (`Period.codesEnd`), so
   a bedtime period that makes a ring staged run longer leaves the stages at their clock times and the extra time
   without stage data (issue #110). kv `night_finder` holds the rules version. `Analyzer` then
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
3. `NightAnalyzer` computes durations, efficiency (time asleep over time in bed, as Oura defines it; steps without
   stage data count as time in bed, neither asleep nor awake, and the Sleep page shows them as "No data", issue #107; the screens and the averages work efficiency out from the
   stored minutes, `StageShare.efficiency`, so nights analysed before agree without a re analysis), latency, wake ups, cycles, lowest heart rate, HRV,
   breathing, SpO2 and nightly skin temperature.
4. `Scores` combines contributors with the weights open_health recovered from Oura's engine (Sleep
   35/15/10/10/10/10/10); a period with no sleep at all has no Restfulness (issue #118), like REM and deep on a
   night without stages; contributor curves are transparent and based on sleep research. Baselines become
   personal after about 14 nights.
5. Time zones (issue #22): each night takes its zone from `zones` and stores it as `zone`; its day, sleep timing
   and clock change marker use it, and a day uses its main night's zone. Screens format each night's times
   with its own zone (`Json.zone`, falling back to the phone's zone for nights without one), so a trip does
   not move earlier nights. A new zone row analyses the nights since the row before again. A night whose end
   falls between two rows takes the later zone; it keeps the date it has in the zone before only when it was
   most likely slept there, its end nearer to 07:00 on that clock (`ClockChange.sleptBefore`: a night before a
   flight west), so a first night after crossing the date line stays on its own day (issue #162). Screens that show
   one calendar day (Screen clock, the Body tab's day) take its clock change from `ClockChange.onDay`: a change
   belongs to the date of the clock time it skips or repeats, also at midnight (Greenland, Cairo; issue #158),
   while nights keep `ClockChange.find`. A touch on a lit 5 minute line of the Screen clock finds the use drawn
   there with the step maths of `DialMath.fill` (`DialMath.touch`, issue #109).
6. `Analyzer` writes nights and days; it re analyses the last two days after each sync and everything
   when dating changed or the user asks (Settings, Help). "Everything" is the whole stored history with no
   age limit (a week at a time, so memory stays flat); with years of history that takes a few minutes. Each day summary reads the nights and summaries of up to 104 days before it for its baselines; during one analysis each of those is read and parsed once and kept as the few fields the summaries use (`Analyzer.History`, issue #139: before, the same night was parsed about 200 times, most of a full run). Nothing about it is stored, the results are byte for byte the same (pipeline test), and what is older than the 104 days is dropped as the days move on.
   An analysis replaces its nights and days in one transaction (`Db.inTransaction`, issue #230): the screens keep
   reading the old ones (write ahead logging) until the new ones are all stored, and any stop or failure (also out of
   memory, a full disk or the app being ended) rolls back to them, then `analysis_error` holds the text. Re analyse all
   only writes kv `full_pending` first; the delete of every night and day happens inside that transaction, and a run
   that did not commit is done again by the next analysis. `dated_count`, `event_count`, `analyzed_until` and the rule
   versions are stored in the same commit, so they always describe the stored results.
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
8. The day's activity (`Wellness.day`, Activity tab) counts every real minute from midnight to midnight in the day's
   zone once: 1440 minutes, 1380 or 1500 when the clocks change by an hour (1410 or 1470 for Lord Howe's half hour),
   so neighbouring days never share or lose a minute (issue #114). The 96 quarter hours of "Intensity through the
   day" and the 08:00 to 22:00 window go by each minute's clock time, like `DialMath.fill`: the repeated hour lands
   twice on the same quarter hours (their average), the skipped one stays empty, and a line under the chart says so
   (`ClockChange.activityText`) on days analysed this way: the day JSON stores the real minutes as `day_min`, and a
   day stored before has no `day_min` (its numbers still count 1440 minutes), so it gets no line. A sitting stretch (awake, under 1.5 MET, ended by sleep, a missing minute or more
   movement) is a long sitting period when 60 or more of its own minutes lie between 08:00 and 22:00, also at the end
   of the day (issue #154). Activity values count only from 0 to 25 MET (`EventCodec.plausibleMet`, issue #120);
   higher ones are damaged bytes and count as missing minutes. A minute inside one of the day's sleeps is Rest also
   without an activity value (issue #112; whether a Ring 4 sends values at night is not known), so every minute is in
   exactly one of High, Medium, Low, Inactive, Rest or Not worn (`non_wear_min`); `covered_min` stays the minutes with a
   value. Total burn (`total_kcal`) is active burn plus the resting burn (`Wellness.bmr`); a day still running when it
   is analysed counts the resting burn only for the share of its real seconds that has passed (`Wellness.restingKcal`,
   issue #155, as Oura's total burn grows through the day) and stores `total_until`, which the screen shows as "So far
   (up to 08:07)"; the next analysis after the day ended stores the whole day. These rules have no version of their own: stored days
   keep their numbers until they are analysed again (the last two days after each sync, or Re analyse all).

## Threading

`RingManager` runs every ring task on one worker thread and guards the ring with one lock (`op`).
Anything that holds the ring (live, games) must release it when its screen pauses (Live since issue #187). Sync waits up to
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
the download is over, Android stopping the job leaves Live, a game or the person's own sync alone (#43). A stop that comes while
the sync still connects is applied as soon as the connection is open, before anything is downloaded (issue #193).

Live (issue #153): `core/LiveSessions` keeps the session tokens. Each start gets a new token, a stop moves
the current token on, and the running mark holds the token of the newest session queued or running.
`startLive` rejoins only when that session is still the current one (`RingTasks.rejoinLive`, applied by
`LiveSessions.start`); a session that was stopped (turned phone, Done and back) and is still cleaning up
gets a new session queued behind it on the worker, which waits for the lock as usual. A queued session
that became stale, or one that ends, clears only its own mark (`stale`, `ended`), so an older session
never clears a newer one. Messages of an ended session only go to the log. The Live screen measures only while it is in front (issue #187,
`core/LiveScreen`): `onPause` stops its session, so the ring is free for a background sync once the session has left its
loop and cleaned up (seconds), and `onResume` starts a new one by the rules above. A session that was stopped
while it waited for the ring gives the lock back untouched once it gets it, and one stopped while it connects, checks
the key or does its first download ends at the next of those steps, before any live mode is switched on (and then puts
no mode back). Values, charts and holds stay on screen.

A long analysis (Re analyse all, or more than 28 days to analyse, `Analyzer.LONG_RUN_DAYS`; issue #230) keeps its
transaction open for minutes, and on Android every write from another thread waits for it. So before it opens the
transaction it marks the history (`Db.beginLongRun`) and waits for ring tasks that store what they read (a sync download,
Live, factory reset; each takes `Db.holdForRing` while it holds the ring). While it runs, a sync waits before it takes
the ring, with the status line "Re analysing your history, this takes a few minutes. The sync starts after it." (a stop
still reaches it), and Live and factory reset do not start and say so (`SyncError.REANALYSING`). Inside the transaction
the analysis calls nothing synchronized on `Db` (`rebuildTime` and the zone note run before it), so a thread that waits
for the transaction can never hold what the analysis needs. Shorter runs (the analysis after a sync) are one
transaction too and mark nothing: a write waits those seconds. `Analyzer.running()` counts analyses running or waiting
for the lock (review of #243): while one does, the main screen's load skips the zone note, the screen log and their
analysis (`Analyzer.beforeLoad`) and shows the stored nights at once; entering demo mode waits for the analysis (its
progress says "Waiting for the re analysis of your history to finish"), and leaving it is refused with "Re analysing
your history, this takes a few minutes. Try again then." (`Demo.leave`, `Analyzer.whenIdle`), since the switch closes
the database the analysis writes in. Re analyse all started while a ring task runs says it waits for that task first.

Code that replaces or wipes the database first waits for a running analysis (`synchronized
(Analyzer.class)`): restore, pairing a different ring, and "Delete all local data" (`Analyzer.deleteAll`,
one transaction). Delete also runs inside `RingManager.whileRingFree`, which holds the ring lock and
refuses while a sync is under way, so no download or analysis writes nights or events back after it
(issue #43). Swapping the database file (restoring a backup's history, entering demo mode) must not meet a
ring task either: a download keeps its own `Db` object, and a closed one reopens by file name, so its next
batch would land in the restored file with the old cursor and epoch, and a sync's analysis would run on the
demo file (issue #219). The ring lock and the sync count therefore live in `core/RingGate`, shared by
`RingManager` and the swaps, and a swap runs through `RingGate.whenFree`: it waits while a sync is under way
(download to the end of its analysis), a ring task holds the lock, or an analysis runs, holding nothing
meanwhile (so it cannot deadlock with a long run, which never asks for the ring and whose `beginLongRun` waits
only for ring tasks, all holding the lock), then swaps with the ring lock held and the analysis lock inside
it (the order pairing uses). Setup's restore line and the demo dialog say "Waiting for the sync with your ring
to finish" (or the re analysis line). After 10 minutes (`SWAP_WAIT_MS`) or an interrupt it gives up and changes
nothing ("The ring was still syncing, nothing was restored. Try again once the sync has finished.", for the
demo "A sync was still running, demo mode stays off. Try again."). A sync that started just before demo mode
was turned on checks again once it has the ring and ends before it connects. Leaving demo mode needs no wait
(no ring task runs in demo mode), restoring only key and settings swaps nothing, and pairing a different ring
wipes in place with the ring held. Analysis and database reads in the UI run in AsyncTasks.
A task that must outlive a turned phone (screens are rebuilt, no `configChanges`) holds only the
application context and reports through a holder to the screen attached now: `DemoRun` in
`MainActivity` (#29) and the repair in Data check (`LogActivity.REPAIR`, a `core/OneRun`, #130).
Pairing in Setup reports through `SetupActivity.PAIR` (a `core/Pairing.Run`, #218): a static callback holds no screen,
a result that ends while no Setup screen is attached waits for the next one (`take`, once), a rebuilt screen shows
"Working" in the card that started it, and one listener per screen shows the ring's status line there.
Settings ring actions (read or switch measurements, factory reset) report to `SettingsActivity.SHOWN`, the
screen resumed now, with the states the starting screen knew (the analysis after "Count phone use as awake"
redraws the stage detail preview there, only that card, #204); a reset closes every Settings screen by
opening the main screen with `CLEAR_TOP` from the application context, and Settings without a key sends
the person there on resume, where Setup opens (#59). The main screen finishes a cut off demo whenever demo
mode is on and no preparation runs (`Demo.finishOnStart`), also when the app is opened from the launcher
after Android ended it (#69).

## Ring finder (issue #74)

`ui/FinderActivity` (Today under the score rings, Settings, Ring) listens without connecting, so a sync
can run meanwhile; only the "on a finger now" check and Make it blink connect, for seconds (below). Everything stops in `onPause`.

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
  whole (heart rate may stop while the hand moves); one without counts only when `hrRule` is off. A heart rate older
  than a stretch's first warm reading counts for it (in this step and at the end) only when it is not older than the
  cool readings before the stretch (issue #135): taken off, a few minutes on the table, then the charger is no longer
  confirmed by the heart rate from before, while one measured during the cool readings (cold fingers) still counts. Lone
  values stay bounded by the neighbouring cool readings. After a single cool reading (5 minutes on a table cannot be told
  from a cold hand), over an hour warm without heart rate stays `UNSURE` since the heart rate before it. A heart rate needs one from another stored event within 30 minutes
  (the values of one HRV event or AOHR burst never vouch for each other, so `lastWorn` passes an event
  number per heart rate, and pairing is worked out first because the rule uses it); a lone one counts only next to a finger reading or after a warm newest reading (else it is a stray).
  Uncounted warm readings neither end nor start a cool stretch. At the end: warm with heart rate in the
  last hour (only such a heart rate confirms it on the card), or warm for under an hour with no heart rate yet (just put on), is worn; warm with heart rate
  at first but none for over an hour is `UNSURE` (charger, duvet) at the last heart rate; warm for over an
  hour without any heart rate near is ignored and the cool readings before decide. A charger right after
  taking it off (no cool reading between) reads as worn until it leaves the charger; with two or more cool readings between,
  it reads "probably just put on" for its first hour, then taken off. The card text (`LastWorn.text`, the app passes its time format) lists what
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
  Setup without a saved ring takes the ring with the strongest plausible signal (`Proximity.strongest`; a ring
  keeps its last plausible reading of the scan, `Proximity.replaces`), so a 127 never wins (issue #105).
* Permission: after a request comes back without it, the screen says "Android no longer asks" and offers the app's
  settings only after a refusal without Android's explanation that follows an explicit one (`core/PermissionAsk`;
  `Prefs.permissionDenied` remembers that Android explained it once, set by `Permissions.request` and cleared once
  granted, also when Main or Find my ring resumes with it granted, since Android may take an unused app's permission
  back and then asks afresh), or after two such answers in a row on the screen (a refusal from before the app
  remembered it: the first Allow shows no dialog, the second leads to the app's settings). `PermissionAsk.answer`
  holds the whole decision the screen takes.
  A dialog closed without choosing or an interrupted request keeps "Allow" (issue #140).
* The screen is locked to portrait (the walk assumes an upright phone, and turning on the spot would
  otherwise rotate the screen and lose the walk) and handles size changes itself (issue #96).
* One screen without scrolling (issue #191), top to bottom: a top bar with the "Clicks on/off" chip
  (`Prefs.finderSound`), a last worn strip (`LastWorn.brief` plus when this phone heard it; a tap opens the full
  `LastWorn.text`, last heard, battery and the explanation; its right end holds the chip of the "on a finger now"
  check of #192), the instruments (the gauge with band and signal line, then the signal trail strip of the last
  2 minutes, touch readable), and the map card, which takes the height left over: the turn dial with you in the middle
  before walking and while turning, the mini map after 1 m, the status line, Start and "How it works" (the steps,
  notes, limits and step length). `core/analysis/FinderLayout.plan` shares the height (screen height in dp and font
  scale in): the map gets 140 dp first, the gauge 72 and the strip 40, a compact plan (smaller paddings, 12 sp status)
  when that does not fit; spare room goes to the map up to 200 dp, the gauge up to 96 and the strip up to 56, the map
  up to 260, then gauge 104 and strip 72, the rest to the map. The screen re plans when its height changes. The texts'
  heights are estimates; when even the compact plan does not fit (a very large font) or the details are open, the
  page scrolls instead of cutting anything.
* On a finger now (issue #192, `core/FingerCheck`): once per visit, as soon as the ring is heard, Bluetooth is on, no walk
  is recorded and no other task holds the ring (`RingManager.ringHeld`, `Db.longRun`; both make it wait and ask by itself
  afterwards), `RingManager.askFinger` takes the ring like Live (the `op` lock for up to 10 s, `Db.holdForRing`, refused in
  demo mode by `open`), reads the battery, downloads what is new with `drainNew` (stored like Live and a sync) and lets go.
  It does not run `rebuildTime` (issue #121): `core/analysis/FingerNow.Reader` reads the newest skin temperature and heart
  rate from the downloaded events, the newest event being "now" (the phone anchor's rule), a jump back of the counter by
  over a minute starting again (reboot). `RingManager` keeps that reader across asks for one boot epoch of one ring (its
  events are stored undated, so `RightNow.load` does not see them yet) and ages it by the phone time since its download;
  of each reading the newer of the kept one and the stored one (`RightNow.load`) counts. The next analysis dates the new
  events first, so the check's time does not grow with the history; until then Settings, Ring calls them "Dated at the
  next sync" (`Db.undatedAllNew`) instead of "Waiting for a time anchor". Rule (last 20 minutes): 27 °C or more with a
  heart rate within 5 minutes of the newest temperature (or of now) is a finger; without one it is "probably the charger"
  when the battery went up since the read before (`LastWorn.charging`, read at each ask), else "finger or charger"
  (review of #245: a heart rate from before the ring went onto a warm charger must not vouch for it); cool with a heart
  rate in the last 5 minutes is a finger (cold hands), with one 5 to 20 minutes old it stays open ("cold hands or just
  taken off"), without any it is not on a finger. The answer replaces the strip's line and its evidence leads the opened strip; an answer under
  2 minutes old is kept when the screen comes back. The strip's chip: "Make it blink" (connects again, notes the daytime
  heart rate mode, switches an off mode to automatic first like Live, switches it to live and starts the on demand
  measurement for 30 s, downloading every 5 s so a measured heart rate updates the answer, then switches the measurement
  off and puts the mode back, each step on its own; after a lost link the ring falls back to automatic by itself, not to
  off), "Stop" while connected, "Ask again" after a failure, nothing while a walk or turn is recorded (Start stops a
  running ask or blink first, `FingerCheck.walkStarts`). Waiting for another ring task says "Asks the ring once it is free." While the finder holds the ring the instruments say hot and cold pauses. Demo mode never
  connects: a made up "not on a finger" answer that fits the demo walk, and a blink that only counts down.
* Turn on the spot: `core/analysis/SearchWalk` records every reading with the heading from
  `GAME_ROTATION_VECTOR` (phone upright against the chest) and fits one wave over 12 directions for the
  strongest one; a direction faced without hearing the ring counts as 6 dB below the weakest one heard.
* Search walk (phase 2): `core/analysis/StepDetector` counts steps from the accelerometer (registered
  only while recording; no activity recognition permission; a second bump within 250 ms is the same
  footfall), each step moves the person 0.41 × height along the upright heading of its own time (relative to the
  first upright heading after Start, since the sensor's zero is arbitrary). `StepDetector` reports the first step
  of a run only with the second (`lastTimes` gives both times), so the app passes each step with its own time
  (`SearchWalk.step`) and a step's heading comes from the last 5 s of headings; a pair passed as `steps(t, 2)` is
  judged as one, so the second cannot confirm the first (issue #108). Turning on the spot (issue #108): how far a
  step turned is judged on the mean heading of the 200 ms before it; a step that turned 5° or more from the step before
  (the first from the Start heading) waits, further steps rotating the same way wait with it (while the rotation is
  under 60°, one step that does not turn on is allowed in between, so two corners with a step between stay two
  corners). When the rotation ends (or at Stop, `finish`) the waiting steps move along their own headings (a corner
  or curve), unless at least two of them rotated 150° or more in all and fast enough per step (`spinNeeds`: 20° a
  step at 150°, falling to 10° at 300° and beyond): then they were a turn on the spot and stay where they are. The
  app stamps headings and steps from the sensors' own time, on one clock. Limits: from the heading alone a curve and
  a turn on the spot look the same. A U turn in 2 to 4 steps counts as a pivot and its steps stay (under a metre
  off); a full loop around a table at 10° a step or more, or a slow turn on the spot at under 10° a step, is
  judged wrongly, and so is a half circle walked tightly (round a table, about 2 m across, at 20° a step or more): it
  counts as a turn on the spot and its steps stay (at 20° a step its 9 turning steps, at 30 to 45° a step 6 to 4).
  While 2 or more steps wait, or the waiting steps turned 60° or more, the card says how many (`waitingToShow`; the
  sway of walking keeps one step waiting most of the time, which is not worth a line). Readings taken while steps
  waited move to those steps' places; otherwise every reading keeps its place. Once the walk spans 2 m in two
  directions (at least 1 m wide, 6 s of readings), `SearchWalk.locate` fits signal = level −
  10 n log10(distance) − body shadow over a 0.2 m grid (walk plus 2 m), n 2 to 3.5, and gives a
  likelihood weighted centre and radius (1.8 × spread, at least 1 m; none above 3 m or at the grid's edge).
  Out of hundreds of spots the best one explains some of plain noise, the more the fewer readings (issue #136),
  so a circle also needs an F value of 5 against a flat signal with the 5 fitted values (`neededShare`: the model
  must explain 25 / (25 + half seconds − 6) of the signal's changes, 81 % for 12 half seconds, 64 % for 20, 32 % for
  60, never under 15 %). A circle whose middle lies outside the walked area is labelled "Here or farther out" and the
  text says the ring may also be farther that way. `ui/MiniMap` draws it; under 1 m
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
