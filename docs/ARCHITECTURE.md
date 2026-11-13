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
| `core/` | Pure Java, no Android imports, unit tested on the JVM. `Protocol` (request bytes), `Packet` (framing), `Auth` (key handshake), `RingClient` (requests, history drain, live parsing), `HistoryBatch` and `ExtEnvelopeParser` (history batches), `EventCodec` (event decoders), `TimeLine` (ring counter to wall clock), `Dating` (every event's boot segment and date: all at once, or after a sync only the new part, with the state that needs, issue #220), `Tilt` (games), `SyncError` (plain sentences for failed syncs, pairing, Settings actions on the ring, live heart rate and game motion; which failed sync attempts are tried again, only Bluetooth off, no ring found or a stop end a sync at once, issue #157; a failed sync always stores an error text, issue #91), `Texts` (a number with its word in singular or plural, the toast length limit, the sentence for a failed export), `RingTasks` (which sync a stop reaches, when Live rejoins a session, which sync shows the final status line, that demo mode never uses the ring; issues #43, #153 and #185), `LiveSessions` (the live session tokens: start or rejoin, stop, a queued session that became stale, a session that ends clears only its own mark; issue #153), `LiveScreen` (Live measures only while in front, and in demo mode only made up readings; issues #185 and #187), `DemoLive` (the made up Live readings of demo mode), `FingerCheck` (Find my ring: when it asks the ring whether it is on a finger, its strip line and chip, the 30 s blink; issue #192), `PermissionAsk` (whether Android still shows the permission dialog), `BackgroundJob` (whether the background sync job is scheduled, kept or cancelled, issue #193; a sync or attempt under 30 minutes ago makes the job and the main screen skip theirs, issue #194; `due` decides what one run does, issue #329), `MainDevice` (one set of numbers: the main device, the others only in its gaps; one `Source` per device: ring, watch and phone, issue #329), `DeviceHistory` (the watch's or the phone's days over time without a ring: each day's dial scores by `DeviceScores.score`, steps, calories, workouts, nights with their lowest heart rate, sleep debt, the usual resting heart rate, cardio capacity; owner's report of 13 November), `PhoneSteps` (the phone's step counter: readings to intervals, restarts by boot count, spreading over days, its Data check lines and texts, the demo's readings; issue #329), `Start` (Getting started: which screen opens, the start choice, which device cards each tab shows, issue #373), `Pairing` (the order of the pairing steps so a key written to a ring is never lost, and the hand over of the result to the Setup screen shown now, issue #218), `RingGate` (the ring lock and the count of syncs under way, shared with the code that swaps the database file, issue #219; `takeIfFree` for the Watch check, which never waits for the ring), `WatchCheck` (the Watch check's report: UUID and property names, the 2 byte rule, the copy limit, the demo example; issue #251), `SetupTasks` (Setup never restores and pairs at once, review of #248), `SyncStart` (the start of a sync: any error gives the ring lock back and ends with a safe text, issue #334), `ModeRestore` (the measurement modes Live and the finder put back, also at the next connection after a lost link; issue #221), `Backups` (the automatic backups' rules and texts: when a new version copies the history first, the names of the copies, which copies may go, when a folder copy is due; issue #242), `PeriodRemind` (the reminder before the expected period: when, for which cycle, why none, its texts; issue #252), `DripCsv` (reads drip's export by column name, its made up demo file and mouth temperatures; issue #252) and `DripImport` (the drip import's preview and the one decision per day and temperature the import applies), `Hex` |
| `core/withings/` | The Withings watch sync (issue #251, phase 1b): `Frame` (message frame, parts, pieces and joining them), `Proto` (message and part types, the requests, the hello's and battery's answers, workout names), `Records` (the decoder, versioned), `Session` (the message order over a link, the ring always wins), `WatchDays` (per day sums, the night, battery drop, zones), `WatchDue` (when a background sync is due), `WatchDemo` (made up messages for demo mode and tests) |
| `core/analysis/` | `NightFinder` (where the sleeps are), `Stager` (sleep stages), `ScreenTime` (phone screen sessions, apps in front, their effect on a night), `ScreenScore` (screen score), `NightAnalyzer` (night metrics), `WakeUps` (the one definition of a wake up, a minute or more awake inside the night, used by the Sleep page, the timeline and the Settings preview; issue #70), `Scores` (Sleep, Readiness, Activity), `Wellness` (activity, calories, sleep debt, radar, VO2 max), `SleepPattern` (average night), `StageShare` (stage percentages that add up to 100), `LiveFeed` (sorts a live refresh: real beats apart from always on heart rate, issue #152), `FinderLayout` (how Find my ring shares the screen's height, issue #191), `FingerNow` (on a finger now, from what the finder just downloaded, issue #192), `LiveHrv`, `LeftOut` (the times left out because someone else wore the ring, merged; the sleep window without them; their texts; issue #254), `Cycles` (the period tracker's estimates from the logged days: periods, cycles, the next start and its range, late, the calendar ovulation estimate and fertile window, unusual cycles, the cycles over time, "My periods have stopped" and its question, their texts, the demo's made up log and warmer nights; issue #252; with `measured` the estimate from the temperature rise), `TempRise` (phase 2 of #252: the three over six rule on one cycle of one signal, each cycle with the ring and the mouth, the own time from ovulation to the period, their texts), `RiseBacktest` (issue #383: the estimate from the temperature rise replayed on each past cycle from the first day it would have shown, with the log and nights up to that day only, against the calendar of that day), `cycleforecast/` (issue #383: the period prediction methods behind one interface, `Method.predict(lengths)` giving a `Forecast`: Open Ring's rule unchanged, drip style, exponential smoothing, an autoregressive model and a Bayesian model with Bull 2019 as its start that counts a probably missed period as two cycles; `Backtest` runs each walk forward on the person's own cycles, `best()` is Settings' default Best for you), `Badges` (issue #265: badges for days and nights that were good compared with the person's own history, their rules, evidence numbers and texts; one pass over the dates, nothing stored; shown from phase 1b), `Stats`, `TS` (time series) |
| `data/` | `Db` (SQLite; real and demo files), `Analyzer` (runs analysis after each sync), `Json` (night and day JSON), `Prefs` (settings, encrypted ring key), `DataCheck`, `RightNow`, `SleepHistory`, `Backup`, `BeforeUpdate` (the copy of the history before an update, its check, going back; issue #242), `AutoBackup` (the automatic backup job's run and going back to a copy), `Demo`, `Exporter`, `Periods` (the period log: days, ends, the restore merge; issue #252), `PhoneStore` (the phone's step readings and the intervals worked out from them, the restore merge, the Data check lines; issue #329), `WatchStore` (the watch's raw messages, the rows worked out from them, the restore merge, the Data check lines; issue #251), `WatchHistory` (watch nights as the ring's night objects for the strip, the timeline and Further analysis), `ScreenUse` (copies Android's screen on and off log and which app was in front), `ScreenDays` (screen time per day for the Screen tab, with apps left out), `BadgeDays` (issue #265: what the badges need for a range of dates with one read per table; the Today row reads back until its badges are exactly the whole history's, `Badges.recent` and `exactFrom`, since "at most once in 7 days" chains back through a streak; screen minutes and the evening counted with the Screen tab's own code, `ScreenDays.counted`, `span`, `nightRead`, `bedMin`) |
| `ble/` | `GattLink` (connection, MTU, bonding, notifications), `RingScanner` (also the unfiltered scan of the Watch check, in the same start budget), `WatchLink` (one short look at a watch without bonding, issue #251), `WatchSync` (one sync with the watch: its own link and lock, the ring lock taken without waiting, issue #251 phase 1b), `RingManager` (sync, live, motion, pairing; one worker thread and one lock so only one task uses the ring) |
| `sync/` | `SyncService` (foreground sync), `SyncJob` (every 3 hours; scheduled only when no equal job is pending, since replacing a job stops it while it runs, `core/BackgroundJob`, issue #193; skips a run within 30 minutes of the last sync or attempt, issue #194), `BackupJob` (the automatic backups: one daily job while charging, and a one off check after an update; issue #242), `PhoneJob`, `PhoneReader` and `PhoneStepsReceiver` (the phone's step counter about every hour, when the app opens and at a restart; issue #329), `PeriodReminder` (the reminder before the expected period: its alarm, the notification, planned again after a reboot, a zone or clock change and an update; issue #252), `SafFolder` (the folder the person picked, through Android's storage access framework) |
| `ui/` | `MainActivity` (tabs, date, follows the newest day), `Pages` (content of every tab), `Charts` (hand drawn views; `Scrub` adds touch readout), `Ui` (colors, cards, buttons, inputs), `Explain` (texts), `DayData`, `PhoneViews` (steps from this phone on Activity, the "Add a ring" helpers, issue #329), `DeviceViews` and `DeviceIcon` (the tabs without a ring and the drawn device icons, issue #373), `HistoryViews` (the statistics at the bottom of Sleep, Ready, Activity and Body without a ring), `SetupActivity`, `SettingsActivity`, `LiveActivity`, `LogActivity`, `WatchActivity` (the Watch check, issue #251), `WatchPageActivity` (the Watch page, phase 1b), `PeriodCard`, `PeriodCalendar` and `PeriodAnalysis` (the period tracker on Body and Today, its cycles over time and Body's temperature chart with the cycle on it, issue #252), `BadgeViews`, `BadgesActivity` and `BadgeCalendar` (Recent badges on Today, the Badges page; kv `badges_seen` holds the newest badge date seen, in the open file, so demo and real stay apart; issue #265), `DripImportActivity` (Import from drip: the picker, the preview with Correct and Ignore, Import), `Permissions`, `games/` |

## Talking to the ring

* GATT service `98ed0001-a541-11e4-b6a0-0002a5d5c51b`; requests are written to `98ed0002`, replies
  arrive on `98ed0003`. Ring 4 also offers notify channels `98ed0004` to `98ed0006` (subscribed and
  logged, purpose unknown). MTU must be at least 100.
* Framing: `tag | length | payload`, little endian. Extended operations sit under tag `0x2f`.
* Authentication: the app asks for a nonce (`2f 01 2b`), the ring replies with 15 bytes, and the app
  answers with the nonce padded to 16 bytes and encrypted with the 16 byte key (AES/ECB, `2f 11 2d`). Pairing a reset ring writes a new key (`24 10 <key>`).
  The key is stored encrypted with an Android Keystore key (`Prefs`). The Keystore key does not move with a phone to
  phone transfer, so a stored key can be unreadable (`core/KeyState`, issue #199): that is not "no key". The main
  screen says so and leads to Setup, no sync starts, and the stored key is never deleted or overwritten for it; only a
  key the ring accepted, a restore or "Use a different key or ring" replace it. `KeyState.NO_RING` is "no ring by choice"
  (issue #329): Setup's "Use without a ring" sets `Prefs.noRing`, which counts only while no key is stored (`KeyState.of(stored,
  read, noRing)`), and a pairing (`Prefs.pairDone`) removes it. It never opens Setup and never syncs; Sync now, Find my ring, Live,
  the games and the Ring block show "Add a ring" instead (`KeyState.addRingInstead`), which opens Setup at Bluetooth access
  (extra `add_ring`, Back returns), and the main screen does not ask for Bluetooth. Every place that sends a person
  to Setup or needs a paired ring asks `KeyState` (`opensSetup` on the main screen, `needsSetup` in Settings and after a
  failed demo, `hasRing` in Find my ring; `Prefs.ringState` answers without reading the key, so it serves only `needsSetup` and `hasRing`), so none can loop.
  **Getting started (issue #373, `core/Start`):** Setup's first screen leads to "What do you want to use?": Pair a ring (the
  old path), Pair a watch (Bluetooth access, About you, then the main screen with the Watch setup on top) or Just this phone
  (About you, then the main screen). The watch and the phone set `Prefs.noRing` and `Prefs.startChoice` (in the backup)
  before the main screen opens, and `Prefs.keyState`/`ringState` count an added watch as no ring while no key is stored, so
  a watch only phone is `NO_RING` and the main screen (`Start.opensSetup`, which also never opens Setup with a watch added)
  never sends it to the ring setup. The tabs ask `Start.cards(tab, DayData.has)` which device cards to show: without a ring
  no empty ring cards, a short line with Add a ring (`ui/DeviceViews`), the watch's or the phone's day on Today.
  Scores without a ring (issue #379, `core/DeviceScores`): Activity, Sleep and Readiness from the watch, Activity from the
  phone's steps, worked out on each day load (`DayData.devices`, 35 days of rows, nothing stored) with the ring's own
  functions in `core/analysis/Scores` on other inputs (steps per quarter hour, Withings' night, the watch's lowest night
  heart rate); a part the device cannot give is left out and named on the tab. With a ring the ring's stored scores stay
  and the watch's show only as "Watch version" lines; a JVM test fingerprints 9,000 ring scores to prove them unchanged.
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
  dates the events after each sync, which also repairs interrupted syncs. It works the result out in Java
  and writes only the rows whose segment or date changed (issue #21); the pipeline test keeps the old full
  SQL rewrite as the reference and checks every row against it. Since issue #220 it does not read every row
  each time: kv `time_state` (written with the dates in one transaction) keeps per boot segment its id and
  counter range, the last two time records of the newest one, counts, the range of dated rows and bounds for
  the 6 hour limit. With it `core/Dating.step` reads only the new rows, the old rows of the newest segment from
  the midpoint between its last two time records on (index `ev_seg_ds`; only those can get another date when
  new records follow the old ones) and that segment's `time_sync` rows (a phone anchor can change segment when
  it grows). Everything is dated again from scratch (`Dating.all`, the same code as before) without a readable
  state (first run after the update, a backup from an older version), for Re analyse all and Repair, after a
  change of `TimeLine.RULES`, and whenever a case is not plainly one of those (a late or re downloaded time
  record, a phone anchor between two boots or moving to another one, a time record row gone, a record or date
  crossing the 6 hour limit with the new now, a phone clock set back). The report gives the range of dated rows,
  so the analysis does not scan for it, and the earliest date, old or new, of a row whose date it wrote (`changedFrom`,
  issue #188): in the same commit as the dates it lowers `analyzed_until` to that date, so the nights and days of
  events that moved are analysed again (from two days before it), also when as many events are dated as before and
  when the app stops between the dating and the analysis, or Live dated on its own. An analysis that finds the mark
  lower at its end than when it started (Live dated meanwhile) keeps the lower mark, so the next one does those days.
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
* Live and the finder's blink change modes only for their session (daytime heart rate Off to automatic to live, SpO2 to
  requested) and put back the person's mode (`core/ModeRestore`, issue #221): before the first change the mode to go back
  to is kept in Prefs (`restore_mode_2`, `restore_mode_4`; requested and live are never chosen in Settings, so a ring found
  in them counts as automatic, and a mode still pending wins over what the ring reports). It is cleared only once the ring
  accepted the restore, each measurement on its own. A session that ends without a link leaves it pending; the next sync,
  Live, Find my ring or Settings read or switch of measurements sends it after the key check. A Settings switch wins over
  a pending mode, so do the automatic modes a new pairing sends once the ring accepted them; a factory reset and "Use a different key or ring" drop them.
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
| `excluded` | Times the person left out because someone else wore the ring (`start`, `stop`, `made` as the key, `note` unused; version 7, issue #254). Adding or removing one writes the row and lowers `analyzed_until` to its start in one commit (`Db.addExcluded`, `removeExcluded`). Restored older backups get it empty; Delete all local data and pairing a different ring empty it |
| `period_days` | The period log (version 8, issue #252): one row per calendar day (`day` as yyyy-mm-dd text, so travel never moves it), `flow` -1 removed by the person ("Nothing": a removal mark, never a delete, so a restore cannot bring the day back), 0 spotting, 1 light, 2 medium, 3 heavy; `src` 0 typed, else the import's id; `prev` the amount an import replaced; `made` unix seconds. Written one statement at a time (`Periods.setFlow`) |
| `period_ends` | Days marked as the end of a period (`day`, `made`, `gone`; version 8). Clearing an end keeps the row with `gone` 1 (a removal mark like a day's -1), so a restore cannot bring it back; readers leave those rows out |
| `period_temps`, `period_imports` | The drip import (phase 1b of #252, version 8): one imported basal temperature per day (`day`, `time` as HH:MM, `c` in °C, `excluded`, `src`, `gone`), and one row per import (`id` its time in milliseconds, `made`, `days`, `temps`, `gone`). Undoing an import writes `gone` 1 on the import and its temperatures (a removal mark like a day's -1, never a delete), so a restore of an older backup cannot bring them back |
| `battery`, `kv` | Battery history; small key value state (cursor, analysis progress, errors) |
| `watch_raw`, `watch_devices`, `watch_steps`, `watch_hr`, `watch_sleep`, `watch_workouts`, `watch_battery` | The Withings watch (version 9, issue #251): every message as it came, the watch, and the rows worked out from the messages; see Withings watch below. Kept when another ring is paired, carried through a restore, emptied by Delete all |
| `phone_readings`, `phone_steps` | Steps from this phone (version 11, issue #329): every reading of the phone's step counter as it came (`boot` count and `elapsed` ms as the key, `counter`, `wall`), only ever added to, and the intervals worked out from them (`boot`, `elapsed` of the end reading, `start`, `stop`, `steps`); see Steps from this phone below. Kept when another ring is paired, carried through a restore, emptied by Delete all |

The period tables are the one part of the file the person types and no ring can send again. Pairing a different ring
(`Db.resetRingData`, every table in `Db.RING_TABLES`) keeps them; Delete all local data (`Db.resetAll`) wipes them with the
rest. Both lists come from `Db.SCHEMA` (issue #272): `Db.ALL_TABLES` is every table, `Db.RING_TABLES` every table except
the keep list `Db.KEPT_TABLES` (`Periods.TABLES`, the watch's `WatchStore.TABLES`, issue #251, and the phone's `PhoneStore.TABLES`, issue #329), so a new table is wiped by default and keeping one takes an entry in that list. The pipeline
test fills every table that really is in the file (`sqlite_master`, not `SCHEMA`) and checks Delete all empties each.
After either delete commits, `Db.shrink` runs `VACUUM` and then `wal_checkpoint(TRUNCATE)` (in WAL mode a checkpoint
before VACUUM leaves the file at full size), so the file gives the history's space back; an error there never reaches
the person because the delete is already complete, and a read open at that moment only delays the shrink to the next checkpoint.

Reading phone use (`Db.screen`, `Db.apps`) stays bounded by the time asked for, not by the history since install (issue
#223): rows up to a day long are read from the index on start, from one day and a second before the window, and rows longer
than a day through their own partial indexes (`screen_long`, `apps_long`, `WHERE stop - start > 86400`); the two parts never
share a row and together are exactly the rows of `stop > from AND start < to`. `apps_start` (start, pkg) also makes
`appsStart` one index step. The indexes are made in `Db.onOpen` with `IF NOT EXISTS` (no version step, no row changes), so any
file gets them at its next open, also a restored or older one; SQLite keeps them up to date with every write, so nothing can
go stale, and without them (a file opened read only) the reads are the same, only slower.

A failed shrink is noted for the Data check (issue #299): one kv row `DataCheck.SHRINK_ERROR` written after the delete
committed, with the step, the time and the error's class and place (never its message), gone with the next delete and
removed from a restored file in `Db.replaceWith` (the restored file is compact). The Data check never names the period tables,
their dates or their counts.

**The period tracker (issue #252, phase 1a1).** Shown when the profile says female, when the Settings switch "Show the
period tracker" says so (`Prefs.periodChoice`, -1 untouched, 0 hidden, 1 shown; it travels with a backup), and always in
demo mode (`Cycles.shown`). Hiding deletes nothing. `DayData` reads the whole log with `Periods.cycles` (every day and end
mark, a few hundred rows for years of logging) only when the tracker shows, and `core/analysis/Cycles` works out the
periods (gaps of at most 2 unlogged days; an end mark closes a period on its last logged day on or before the mark;
without one it is over after 3 days without a log; spotting never starts or extends one; removal marks are no entry),
the cycles (start to start; one more than 10 days from the median is left out), the typical length (median under 5
cycles, then the mean of the newest 12 kept without the shortest and longest), the next start with its range
(max(1, round(1.28 × SD)), about 4 in 5; 29 or the own median, give or take 5, with fewer than 3 cycles), late and
waiting (after twice the typical length), and the calendar ovulation estimate (next start minus 14, give or take
max(3, round(1.28 × √(SD² + 2.4²)))) with the fertile window (the 5 days before and the day). Nothing of it is stored:
it is worked out on each load. A tap on Heavy, Medium, Light or Ended (`ui/PeriodCard`) writes one row in the
background for the day shown and loads the page again; it starts no analysis, and Undo puts back the row it changed
(never across demo mode). The month calendar (`ui/PeriodCalendar`) reads a day while the finger slides and opens the
day editor on a tap without movement; the estimates show as bands, never as one marked day. Errors on the way go to the
log as the error's kind only, never with a date or an amount, and never to the Data check.

**Phase 1b: import from drip.** No schema change: the tables of version 8 were made for it. Settings, card Period tracker,
"Import from drip" opens `ui/DripImportActivity`: Android's file picker (`ACTION_OPEN_DOCUMENT`, `text/csv` and `text/*`, no
permission, no network), the file read in the background (at most 32 MB, UTF-8) and parsed by `core/DripCsv` by column name:
`date` and `bleeding.value` must be there (else a plain sentence refuses the file), `bleeding.exclude` true is kept as
spotting, `temperature.value` (above 45 is °F, converted; outside 34 to 43 °C left out), `temperature.time` (HH:MM) and
`temperature.exclude`; every other column is counted per kind for the summary and not stored (drip notes are counted, not
stored, as decided). A BOM, CRLF or CR line ends, quoted fields with commas, quotes and line breaks, missing and extra
columns, any row order, empty lines and duplicate dates (the last row of a date wins as a whole, counted) are read; rows
without a readable date or after today are left out and counted. The preview (`core/DripImport.preview`) compares the file
with the log read once (in demo mode the demo's): periods by `Cycles`' own rules, short ones (under 3 days) each on a line
with Correct (the calendar of the Body card without the estimate's bands, its day editor changing only the preview's days;
`PeriodCard.editor` takes a callback) and Ignore, plus Ignore all; days already logged win (`DripImport.day`: a day with an
amount that differs is kept unless "Take the file's values" is on; a removal mark is no entry), temperatures already there
are never replaced (an undone one is no reading). Nothing is stored before Import. `Periods.importDrip` writes it all in
one transaction and makes each decision again inside it from the rows there now, so a day logged since the preview still
wins; rows it adds carry `src` = the import's id (its time in milliseconds, one higher while taken), a replaced amount goes
into `prev`, and with nothing to write (the same file again) nothing is written, not even an import row. Undo
(`Periods.undoImport`, Settings "Undo the drip import of 30 Oct", one transaction) gives each day the import still holds its
`prev` back as typed, or a removal mark that keeps the import's id where it added the day, and writes `gone` 1 on its
temperatures and the import: exactly its rows, nothing deleted, a day the person changed since is typed and stays; twice
changes nothing. Limit: a day an earlier import added and a later one replaced comes back as typed when the later one is
undone, so undoing the earlier one leaves it. The import never runs in demo mode (the screen shows only the preview and
checks the file it writes is `openring.db`); the demo has its own made up mouth temperatures as one import in its file
(`DripCsv.demoMouth`, `Demo.periods`). The night temperature card shows the imported temperatures by mouth of its days as
a second line in their own colour on their own °C scale (right), a reading drip left out as a hollow dot, the touch text on
two lines. Backups, restores (the phone's rows win, so an undone import stays undone) and Delete all treat the two tables
like the rest of the log; the Data check, the log and the daily CSV hold nothing of it (the period log has no CSV of its
own; the full database export holds it). Errors go to the log as their kind only.

**Phase 1a2: the cycles over time, the night temperature, the reminder, "My periods have stopped".** Nothing new is stored in
the database (no schema change); four settings are added (below). At the end of Body, "Your cycles" (`ui/PeriodAnalysis`)
shows today's cycle day, the days to the expected day, the regularity line, and cycle length, period length and amount over
time: each cycle or period a dot at its start, touch readable, next to the typical length (cycles, with the estimate's range
as a band) or the average, with average, spread (sample SD), shortest and longest. Cycle numbers count every kept cycle (one
more than 10 days from the median left out and counted apart), period numbers and the amount every period that is over (not
the running one); the amount is light 1, medium 2, heavy 3 per logged day, a relative score. Below it the night temperature
line: `temp_dev` of each day summary (left out nights have none) from the start of the period before the last (at most 100
days back, the axis starting at the first night with a value), period days and the estimate's ovulation range and expected
period as bands behind it, every day touch readable, and since phase 2 the rise each cycle shows marked on it (below). `DayData`
reads those day summaries only while the tracker shows. The demo's nights after each made up ovulation (its next start minus
14, up to the night before that start) are 0.3 °C warmer (`Demo.warmNights`, only in `Demo.enter`; `Demo.history` is
unchanged). "My periods have stopped" (`Prefs.periodStopped`, the day it was said; demo mode keeps its own) turns off the
estimate, the late line, the bands and the reminder while no period runs and every bleeding day lies before that day, so a
period logged on it or later ends it by itself (logging also clears the setting); the past cycles stay. From the profile
age of 40, after more than twice the typical cycle and at least 60 days without a bleeding day, the cards ask "Have your
periods stopped?" once; "Not yet" (`Prefs.periodAskLater`) asks again after 60 days. After 12 months while it is said, the
card says so plainly (the badge for it belongs to #265).

**A year ahead, the most likely day, one temperature chart (owner's feedback of 11 November).** Nothing stored changes.
`Cycles.ahead(until)` lists the expected cycles from the next one on (cycle 1 is today's estimate itself; cycle k starts k - 1
typical lengths later, counted from today once the next period is due, give or take max(range, round(sqrt(r1² + (k - 1) × rc²)))
with r1 the next range and rc the calendar's, so √k on the calendar alone; ovulation the luteal days before with the luteal
spread added). The calendar draws every month ahead from it, each range shaded by a normal curve darkest on the most likely
day, which also gets a dashed ring. The card's big line names that day (`Cycles.dayLine`). With the tracker shown and a period
logged, Body's "Temperature deviation, 30 days" becomes "Temperature deviation and your cycle", drawn by `PeriodAnalysis.TempLine`
over `Cycles.tempWindow(shown)` (at least the 30 days before the shown day, from the start of the period before the last, to
the end of the expected range); there is no second temperature chart at the end of Body any more. Touch reads the phase
(`Cycles.phase`). Without a ring the card stays next to Your cycles, where it is the only temperature chart.

**Prediction methods (issue #383).** Nothing new is stored but one setting, `Prefs.periodMethod` (`period_method`, in the backup; 0 Best for you, the default, else a method's id; an unknown id keeps the current choice on restore). `Cycles.withMethod` builds the same log with the chosen method: `Cycles.of` alone stays Open Ring, so its numbers (`typical`, `length`, `sd`, used for regularity, unusual cycles and when the estimate waits) never change; the method gives `step` (the expected length the next start and the months ahead count with), the range and the spread for the ovulation range. Best for you runs `cycleforecast.Backtest` (each method predicts each logged start from only the cycles before it; a cycle more than 10 days from the middle value of the ones before it is not scored) and takes the smallest mean error, ties and fewer than 3 scored cycles to Open Ring. The temperature rise stays a modifier on top (`riseUsed`): never for drip style, and Best for you leaves it out when at least 3 replayed cycles (`RiseBacktest`) say it was further off than the calendar. DayData works out both backtests in the background for Body's card How close the estimates were; the reminder (`PeriodReminder.own`) and Settings' line on what Best for you uses read the same way.

**Phase 2: ovulation from the temperature rise.** Nothing is stored (no schema change, no setting): `Periods.measured` reads
the nights and temperatures on each load (`DayData`, only while the tracker shows; 5 years of day summaries take about 0.15 s
in the JVM test) and `Cycles.measured` returns the estimate with them. The ring's signal is `temp` of each day summary (the
nightly temperature itself, not `temp_dev`, whose 30 night median takes in part of the rise: Experiment view), without a night
of under 3 hours asleep and without every night whose 18:00 to 12:00 touches a left out time (#254), even when the analysis
kept part of it (`Periods.ringNights`). The mouth signal is the drip temperatures in force that drip did not leave out.
`core/analysis/TempRise.find` applies the three over six rule to one cycle of one signal: the baseline is the highest of 6
nights within the 8 days before the first high night (inside the cycle); the first high night and the next ones (at most one
missing day between two) lie more than 0.05 °C above it, the third at least `thirdNeed` above it (the signal's own night to
night noise, the middle step between following nights / 0.954, from 0.1 to 0.2 °C); one exception (a third that falls short,
or one night that dips) needs a fourth; the average from the first high night to the end of the cycle (or today) lies at least
max(0.1, noise) above the six; in a cycle that is over the ovulation lies 7 to 19 days before the next start. A night more than
max(0.5, 4 × noise) from the middle of its neighbours (3 days each way) is left out and named. The first night where all holds
wins; the likely ovulation is the day before. Clear: no exception and a rise of at least 2 × noise; else possible. Each signal
on its own; both within 2 days is clear (the surer one's day, else the earlier), further apart possible and "disagree". A cycle
left out of the estimate (maybe a period was not logged) gets no result. The own luteal length is the middle value of the
newest 12 cycles that are over with a rise (not those that disagree), from 3 of them. With it, the calendar ovulation is the
next start minus that length (give or take max(3, round(1.28 × √(SD² + own SD²))), `OWN_LUTEAL`). Once the running cycle's
rise is found (`MEASURED`), the ovulation is that day (give or take 1 day when clear, 2 when possible: 4 in 5 of the made up
cycles in `CoreTest`) and the next start that day plus the own length (or 14), give or take max(1, round(1.28 × √(luteal SD² +
error²))), luteal SD the own one or 2.4, error 1.0 (clear) or 1.8 days; the calendar's numbers stay in `calendarNext` and
`calendarRange` and the card says both. The reminder follows it too (`Periods.readReal` adds the rise; an error there keeps the
calendar estimate). On the made up cycles of `CoreTest` (rise 0.3 °C, 15 % of nights missing, 3 % warm nights, the first high
night half warm) at noise 0.1 the rule finds 70 % of rises, a clear one within 1 day 87 % of the time, a possible one within
2 days 85 %, mean error 1.0 day; at noise 0.2 it finds 34 % (error 1.5); on flat noise it finds a rise in 2 to 3 % of cycles,
never a clear one at 0.1. The demo (`Demo.enter` only; `Demo.history` and the tests built on it are unchanged): today on cycle day 20, its nights'
night to night spread 0.07 °C instead of 0.12, one fever like night (0.6 °C warmer, 9 days ago, in place of the warm spell of
2 nights lately) that the rule leaves out, so its ring finds this cycle's rise on the made up day and its mouth
temperatures the past cycles' (own length 14 from 3 cycles).

The reminder (`core/PeriodRemind`, `sync/PeriodReminder`): Off (the default), 1, 2, 3 or 5 days before the first day of the
expected range, at a time of day (9:00 at first) on the phone's clock in its zone now, one inexact alarm
(`setAndAllowWhileIdle`, no exact alarm permission). A moment that passed while the range is still to come is sent now. None
without an estimate, while a period runs, after "stopped", once the range has begun, and never twice for one cycle (the
start of its period is noted with `commit` before the notification is posted, `period_remind_sent`, not in a backup). It is
planned again at every app start, after every log change or Undo, after each change in Settings, and by the receiver after
`BOOT_COMPLETED`, `TIMEZONE_CHANGED`, `TIME_SET` and `MY_PACKAGE_REPLACED`; when the alarm comes it plans again first and
sends only when still due. The log is always the person's own: `Periods.readReal` opens the real file read only for one
read (a handler that never deletes a damaged file), also while demo mode is on, without `Db.get`, so no database is opened,
closed, swapped, upgraded or written and the demo's log never schedules anything; outside demo mode, should that open fail,
the app's database is read instead. An error keeps the alarm already set and goes to the log as the error's kind only. The
notification's lock screen version says only "Open Ring reminder". Settings, card Period tracker (also from the button
"Reminders and settings" on the Body card) holds the choices, the next reminder or why there is none, a line when
Android's notifications are off for the app, and "My periods have stopped"; POST_NOTIFICATIONS is asked when a reminder is
first switched on. A backup carries the choices, "stopped" and "Not yet" of the person (never the demo's); an older backup
leaves them as they are.

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
Histories are never merged, with one exception (issue #252): the period log typed on this phone. `Db.replaceWith`
reads every row of the four period tables from the phone's file before the swap (an error on the way refuses the restore
before anything moves, unless it is Android's sign of a damaged file, see below), and after the restored file opened, upgraded (an older backup gets the
tables first) and passed its checks, writes them into it in one transaction, each replacing the backup's row with the same
key, so the phone wins on the same day and a day or end removed on the phone stays removed; only then `.old` goes. Any failure on
the way puts the old file back with its log. A damaged phone file (`SQLiteDatabaseCorruptException` at the open, or from
a damaged page in a period table; such files are kept since issue #255, and a restore is the way out of them) has no log that
can be read reliably and is replaced as before: the restored file then holds only the backup's period log, so days typed on
the phone after that backup are lost with the damaged file, and the restore's result says so ("The period log on this phone
was damaged and could not be kept, ..."). Reading what is left of a damaged log was left out on purpose (it can return wrong
rows or fail half way; review of #275). Ring data is never merged. Going back to a phone copy (`AutoBackup.goBack`) runs the same swap. A backup holds every setting; restored numbers are kept in the ranges Settings
allows, and the stage detail level comes along only with the history or onto a phone without nights (#33).

Export database and a full backup never copy the live file (issue #115). `Exporter.snapshot` waits for a
running analysis, then makes a copy in the cache: `VACUUM INTO` on SQLite 3.27 and later (Android 11 and
newer), otherwise the file and its write ahead log copied while the write lock is held (a sync waits those
seconds). The copy is made a single file and must pass `Db.inspect` and hold at least the events the
history had, before anything is written to the file the person picked; otherwise `Exporter.NotSaved`.

Saving a backup or export from Settings (issue #222) runs in `SettingsActivity.SaveTask`, which holds only the application
and `AutoBackup.RUN` (so one file at a time, also with the automatic backup; the buttons fade and a line says what is
saved, a tap says why nothing starts). The checked copy of the history is made before the picked file is opened, so a copy
that fails its check leaves a file picked to be replaced as it was; a file that comes back while the automatic backup runs
is removed only when it is empty. `core/FileSave` writes the picked file and, after any failure (also past the first
byte, also an Error), removes it; when it cannot be removed and holds something, the sentence calls it incomplete. A file the app does not
live to finish (Android ended it) stays cut off; restoring it says it is damaged and changes nothing. The zip is written at
`Deflater.BEST_SPEED`: packing is about three times faster than at the default level (the whole backup about twice), the file about a tenth larger, read the same by every version.

A backup without the key (issue #288): while a key is saved that this phone cannot read (#199), `Backup.write` (manual and
automatic) leaves the key out and writes `"no_key": true` instead; with no key saved at all it still writes nothing.
Restoring such a file brings settings and history and never touches a key on the phone, readable or not; the ring's identity
(address, serial, model, firmware) comes along only onto a phone without a key, since it belongs to the key. Without a key on
the phone, Setup stays on its page and says to enter the key ("This copy has no ring key, ..."), `setup_done` is not set. An
older version reads no key from the file and refuses it whole with "The backup holds no valid ring key." before it changes
anything. Going back to a phone copy (`AutoBackup.goBack`) needs no key either. An automatic copy without the key is named
`...-nokey.zip` (`Backups.withoutKey`), and `Backups.folderToDelete` never deletes the newest copy with the key for one
without it: it stays beside the kept copies until a copy with the key is written again (review of #340). Older versions do
not know the `-nokey` name and leave those files alone.

## Automatic backups (issue #242)

* **Before an update.** Android gives no hook before it replaces the app, so the new version copies the history on its
  first start, before anything opens it: `BeforeUpdate.ensure` runs first in `Db.get` (after `recoverSwap`) and in
  `Db.replaceWith`, under Db's class lock, once per process. `core/Backups.beforeUpdate` compares the version code noted
  in `Prefs` (`version_noted`) with this one: another version with a real history file is copied first, without a file
  the version is only noted (no noted version but a file counts as an update). The copy is a byte copy of `openring.db`
  and its `-wal` (and `-journal`) to `files/before-update/before-<code>-<version>.partial`, renamed to `.raw` when
  complete, and only then the version is noted (`commit`). A stop before the note leaves a `.partial`, which the next
  start deletes before it copies again; a complete copy for this version is never replaced. In demo mode the real file is
  copied, the demo file never. Free space under the copy's size plus 50 MB, or any failure: no copy, the version is
  noted, the reason kept (`before_note`), and the main screen opens anyway and says so once on Today (until OK). Three
  years (150 MB) copy in about a second in the pipeline test; the main screen's first open runs in its load task.
* **Check.** `BackupJob` (one off, id 4713, battery not low) runs `AutoBackup.run`, which first checks each `.raw`
  copy (`BeforeUpdate.checkPending`): its log folded in (`Exporter.singleFile`), then `Db.inspect` with the full check; it
  becomes `.db`, or `.bad` (kept). The two newest checked copies stay; an older one, checked or bad, goes only once two
  newer ones passed (`Backups.phoneToDelete`). Settings lists them ("Copy from before the update to 1.42 (29 Oct, 210 MB)")
  with "Go back to this copy": `AutoBackup.goBack` checks a temp copy, then inside the restore's swap
  (`Backup.finish` with a step before `Db.replaceWith`, so no sync, ring task or analysis runs) the current history is
  snapshotted into `back-<time>.db` ("Copy from before going back", the newest one stays) and the copy replaces the history;
  key and settings stay. "Delete all local data" deletes the phone copies too.
* **Folder.** The person picks a folder (`ACTION_OPEN_DOCUMENT_TREE`, the permission kept; no storage permission, no
  network). `BackupJob` (periodic, id 4712, every 24 hours with 6 hours flex, charging, battery not low, persisted, kept
  when equal like the sync job, #193) runs daily; `Backups.due` decides whether a copy is due for the chosen interval
  (every day, week or month: due 6 hours before the full interval, or when the last copy lies in the future), so a change
  of interval never replaces the job and a failed copy is tried again the next day. A run (never two at once,
  `AutoBackup.RUN`; never in demo mode) first sends a checked update copy the folder has not had
  (`openring-backup-<date>-<hhmm>-before-1.42.zip`), then makes a regular copy when due: `Exporter.snapshot` inside
  `RingGate.whenFree` (5 minutes at most, else the next run), the zip (`Backup.write`) written as `.partial`, read back
  (every entry, CRC, the history entry as long as the snapshot), renamed, and only then the oldest of the app's own files
  above the number to keep (3, 5 or 10) deleted (`Backups.folderToDelete`). The app's own files are those with its name
  pattern that it listed in `Prefs` before creating them; other files are never touched, our `.partial` files go at the
  next run. A failure keeps a reason code (folder gone or not allowed, folder full, copy failed its check, no space on the
  phone, not written), shown in Settings and Data check; the history is only read. A new folder gets only copies made from
  then on. How often and how many are in the backup file (`backup_days`, `backup_keep`); the folder is not (its permission
  belongs to one installation).

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
   without stage data (issue #110). Two ring stage runs with a break of up to an hour are one night with both
   runs' stages at their own times; the break is awake where the ring's own sleep state says awake
   (`NightFinder.stateAwake`, kept as the ring's own wake up) and has no stage data otherwise (issue #189). Runs
   that overlap (pages sent again) keep the first run's stages. kv `night_finder` holds the rules version (3 since #189). `Analyzer` then
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
6. `Analyzer` writes nights and days; it re analyses the last two days after each sync, from two days before
   the earliest date the dating moved (issue #188), also when older events gained or lost a date (a sync after the
   ring restarted, a late time record, dates past the 6 hour limit that fall due: issue #256), and everything when the
   number of dated events does not add up with no known date (no dated count yet, or a dating since the last analysis
   that this run did not do: one that stopped before its analysis, Live, a version before #188) or the user asks
   (Settings, Help). "Everything" is the whole stored history with no
   age limit (a week at a time, so memory stays flat); with years of history that takes a few minutes. Each day summary reads the nights and summaries of up to 104 days before it for its baselines; during one analysis each of those is read and parsed once and kept as the few fields the summaries use (`Analyzer.History`, issue #139: before, the same night was parsed about 200 times, most of a full run). Nothing about it is stored, the results are byte for byte the same (pipeline test), and what is older than the 104 days is dropped as the days move on.
   An analysis replaces its nights and days in one transaction (`Db.inTransaction`, issue #230): the screens keep
   reading the old ones (write ahead logging) until the new ones are all stored, and any stop or failure (also out of
   memory, a full disk or the app being ended) rolls back to them, then `analysis_error` holds the error's class and
   first place in Open Ring's code (`core/LoadNote`, never its message, which can hold a value; issue #289; the full error
   goes to Android's log and the Log tab, and the Data check leaves out a full text an older version stored). Re analyse all
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
8. Left out times (issue #254, Settings, Advanced): `Analyzer` reads them once per run (`Db.leftOut`, merged by
   `core/analysis/LeftOut`) and drops every reading whose own time lies inside one: events with one time by that time
   (`Analyzer.events`), HRV, activity, always on heart rate and SpO2 value by value (`Analyzer.out`, an HRV event spans 25
   minutes). The ring's bedtime record is dated at its end, so after the phone use step each sleep window goes through
   `LeftOut.window` too (the longest piece outside, dropped under 15 minutes like after phone use), and a piece whose day
   (by the usual date rule) is left out as a whole is not stored (review of #263: the evening before a left out day would
   otherwise give that day a sleep and a score). The bedtime record counts by the window it describes. Nothing about it is
   stored in a night or day: a left out night is simply not stored, so baselines, the period tracker and the CSV (whose
   last column `left_out_min` gives the minutes of each day left out) follow by themselves, and a left out minute of a
   day counts as not worn (`non_wear_min`; the Activity page names no such row, so it says the minutes are not in its numbers). With nothing left out every step is skipped and the results are byte for byte those of the
   version before (pipeline test against a fixed digest); `analysis_inputs` names the left out times only when there are
   some. Adding or removing one holds the analysis lock (`Analyzer.leaveOut`, `bringBack`): the row and the lowered mark
   in one commit, then the analysis from two days before its start to now in its one transaction, so a stop keeps the
   old results and the next analysis finishes it; removing gives back exactly the results from before (incremental
   equals full, #188). Body's Right now skips left out readings; Find my ring and Live do not (they are about the ring).
   The screens draw the left out part of a day as a grey band on Heart rate today (touch reads it), a Left out card on
   Today, Sleep and Activity, and a row next to the night in the night timeline. The raw events are never changed.
   Limits: events with one time (a green IBI beat, a sleep period record, a motion or temperature event) are judged by
   that time, so a reading that stands for the minutes around it can carry a few minutes across an edge. The Settings
   pickers take clock times, so on the night the clocks go back the second 02:00 to 03:00 cannot be picked as an edge.
9. The day's activity (`Wellness.day`, Activity tab) counts every real minute from midnight to midnight in the day's
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
10. A day counts as worn with 60 minutes or more of activity values (`covered_min`, `Scores.WORN_DAY_MIN`, the one name
   for the rule of issue #44). The Activity score's weekly parts and readiness's activity parts (issue #240,
   `Scores.readinessActivity`) leave the other days out: Previous day activity needs yesterday worn, otherwise it drops
   out and its weight goes to the other contributors, and the day stores `prev_day_unworn` (the Readiness page says why);
   Activity balance compares the last 7 with the last 28 stored days with the unworn ones as unknown (still 3 and 7 days
   needed). kv `readiness_rules` holds the rules version (`Scores.READINESS_VERSION`); a change analyses everything again once.

## Threading

`RingManager` runs every ring task on one worker thread and guards the ring with one lock (`op`).
Anything that holds the ring (live, games) must release it when its screen pauses (Live since issue #187). Sync waits up to
15 seconds for the lock, live 10 seconds and games up to 3 minutes; Settings actions on the ring
(read or switch measurements, factory reset) fail at once with "The ring is busy". A sync releases the
lock as soon as its download ends, before it reads the screen log and analyses (issue #21). It analyses only
when something is new (`Analyzer.upToDate`, issue #194): the results store what they were worked out from (kv
`analysis_inputs`: the newest dated event, `analyzed_until`, settings, profile, rule versions, zone and date), and a
sync that stored nothing, with no event from Live or Find my ring since, no new or changed screen session
(`ScreenTime.firstChanged`), the same settings and day and dates the new now leaves alone, logs "nothing new since
the last analysis" instead and summarises only the day still running again, whose Total burn counts up to now
(`Analyzer.skipAfterSync`, review of #249). `busy()`
stays true until the analysis is done, so screens reload only then, and the status keeps saying
"Analysing" (other ring tasks then only write to the log). A sync counts for `busy()` from its start,
also while it waits for the lock, and Settings "Sync now" refuses while `busy()` like the main screen; when
two syncs overlap anyway, only the last one to end sets the final line ("Up to date" or its error), so the
first one's line cannot cover a download still under way (`RingTasks.syncEndStatus`, issue #43).

Stopping a sync reaches only that sync: the background job holds a `SyncTicket`, and `cancelSync` stops it
only while its download holds the ring (`RingTasks.Owner`, one monitor for the check and the stop). Once
the download is over, Android stopping the job leaves Live, a game or the person's own sync alone (#43). A stop that comes while
the sync still connects is applied as soon as the connection is open, before anything is downloaded (issue #193).

The start of a sync (waiting for a long analysis, the ring lock, opening the database, the demo check, `holdForRing`)
runs through `core/SyncStart` (issue #334). Any error there, also an `Error`, gives back the database hold (only when it
was taken) and the ring lock, stores `SyncError.startFailed` ("Sync could not start: " with the error's class and place,
never its message, issue #289) as the last sync error, logs the stack trace and shows the plain sentence (a full or
unwritable storage has its own, `SyncError.Kind.STORAGE`), so the app never ends and the next sync can take the ring.
`SyncJob` finishes its job in a `finally` (the ring part and the watch part each guarded), and `SyncService` turns an
error into its failure notice.

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
still reaches it), and Live and factory reset do not start and say so (`SyncError.REANALYSING`). An interrupt does not
end the wait of `beginLongRun` (issue #260): the ring tasks end on their own, and the interrupt is kept for afterwards.
The pipeline test's SQLite stand in fails a write from another thread while a transaction is open ("would wait on
Android"), so a write that would wait for the long run shows up as a failed test (`ringWriters260`). Inside the transaction
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
it (the order pairing uses). Setup's restore line and the demo dialog say what it waits for ("Waiting for the sync
with your ring to finish", "Waiting for the ring to finish its current task" for Live, Find my ring or a Settings
action, or the re analysis line). After 10 minutes (`SWAP_WAIT_MS`), a stop or an interrupt it gives up and changes
nothing, and the sentence names what held it up (`RingGate.restoreRefused`, `demoRefused`; review of #248). Back on the
demo dialog stops its wait (the dialog can be cancelled only while it waits), and leaving Setup for good stops a restore
that still waits; a swap that has begun always ends. Setup never runs a restore and a pairing at once (`core/SetupTasks`,
review of #248): a pairing during a restore's wait would write a new key to the ring that the restore then replaces on
the phone with the backup's key, so pairing is refused from the picked file until the restore ended ("A restore is under
way. Wait for it to finish, then pair."), and a restore is refused while a pairing runs. A sync that started just before demo mode
was turned on checks again once it has the ring and ends before it connects. Leaving demo mode needs no wait
(no ring task runs in demo mode), restoring only key and settings swaps nothing, and pairing a different ring
wipes in place with the ring held. Analysis and database reads in the UI run in AsyncTasks.
A task that must outlive a turned phone (screens are rebuilt, no `configChanges`) holds only the
application context and reports through a holder to the screen attached now: `DemoRun` in
`MainActivity` (#29) and the repair in Data check (`LogActivity.REPAIR`, a `core/OneRun`, #130), which Settings' Re analyse
all shares (the same job, issue #222): a second tap is refused, a setting saved while it runs (profile, stage detail) makes
it run once more (`OneRun.startOrAgain`, `finishUnlessAgain`), and both screens show it running. Saving a backup or export
file holds `AutoBackup.RUN` the same way.
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
  The texts under the dial and the map come from `core/analysis/WalkText` (each branch tested in `FinderTest`).
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

## Withings watch (issue #251)

Phase 1a is the **Watch check** (Settings, Watch): it learns how a Withings watch talks. Nothing is paired, written to the
watch (besides switching its notifications on) or stored. Phase 1b (below) syncs it.

* **Flow** (`ui/WatchActivity`, `ble/WatchLink`): a 10 s scan without a filter (`RingScanner.scanAll`, counted in the
  same budget of 4 starts per 30 s as the ring's scans, since Android blocks the sixth), strongest signal first once it
  ends; while it runs a row keeps its place and new devices are added below (`WatchCheck.stableOrder`), so a tap checks
  the row under the finger. The
  picked device gets one session of at most 30 s: connect (10 s at most, `TRANSPORT_LE`, no bond requested, balanced
  link speed), list every service and characteristic with its properties, read Device Information (model `0x2a24`,
  firmware `0x2a26`, hardware, software, maker; not the serial number) and Battery (`0x2a19`), switch on
  notifications or indications of every characteristic that sends and is not a standard 16 bit one, listen 10 s,
  disconnect and close. Standard ones are not listened to: they are documented, and the standard heart rate would
  switch the sensor on and send values. Leaving the screen or the app stops the scan and the session at once; Android's
  pairing dialog over the screen only stops the scan (`onPause`), the session goes on until `onStop`, so Cancel there
  does not end the check.
* **The ring always wins:** the session runs through `RingManager.whileRingFree(r, line)`, which uses
  `RingGate.takeIfFree` to get the ring lock at once (no sync counted, no ring task holding it), and holds it until the
  link is closed, so the phone never keeps two links. When the ring is busy the screen says so. Meanwhile `busy()` is
  true and the ring's status line says "Watch check running" (review of #270), so the main screen shows why a sync by
  hand does not start. A ring sync that starts meanwhile waits for the lock as it does for Live (15 s; the background
  job tries again at its next run).
* **The report** (`core/WatchCheck`, kept in memory only): UUIDs (standard ones named, others as they are), property
  names, model, firmware, battery, whether the phone was paired with the watch before and is now (a pairing dialog
  accepted during the check leaves a bond; the screen then tells how to remove it), and per listened characteristic the number of messages and their lengths, plus the
  first 2 bytes (usually the kind of message) of those of the first 3 messages that are longer than 2 bytes. A message
  of 2 bytes or less would be all value, and the later pieces of a long answer start with values (review of #270), so
  the default report holds at most 6 message bytes per characteristic. Why a check ended early is a plain sentence on
  the screen; a status code or error name goes into the report only, on its "Detail" line. The full bytes are only in the second copy, marked private
  (Android 13 and newer hide it in the clipboard preview). At most 300 messages are kept per characteristic, and a
  report is cut at 30,000 characters.
* **Demo mode** never touches Bluetooth: `WatchCheck.demoFound` and `demoReport` go through the same screen code.

**Phase 1b, the sync** (`ble/WatchSync`, `core/withings/`, `data/WatchStore`, `ui/WatchPageActivity`). The Watch check's
report has "Use this watch for syncing" when the device shows the Withings service; that saves its address (`Prefs.setWatch`).

* **Lock, the ring always wins:** one watch sync at a time (`RingGate.watch`, `tryLock`), and the ring lock taken without
  waiting through `RingManager.whileRingFree(r, WatchSync.RUNNING, false)` (no sync counted, no ring task holding it;
  otherwise "The ring is busy right now", and the job tries at its next run). It does not mark the ring busy, so a sync by
  hand, Live or a game is not refused but waits for the lock as usual. Between two messages, and every 250 ms while it
  waits for one, the session asks `RingGate.ringWanted()` (a ring task queued on `op`, or a ring sync counted): then it
  sends "sync done", closes and gives the ring back within about 2 s, far inside the 15 s a ring sync waits. The
  background job hands its own `SyncTicket` to the watch sync: Android stopping that job ends that session only (also a
  stop that came before it started), never a setup, alarm or hand setting the person started. The download takes `Db.holdForRing` like a ring
  task, so a long analysis is waited for ("Re analysing your history, the watch syncs after it"). Never in demo mode.
* **Link:** its own (`WatchSync.Link`, not `GattLink`): `TRANSPORT_LE` to the saved address (15 s), the phone's existing bond
  (the Withings app made it; no bond is asked for), `CONNECTION_PRIORITY_HIGH` only for this session, MTU 119 asked
  (pieces follow what the watch gives), notifications on `0x24`, every request written to `0x24` in pieces of MTU minus 3,
  each piece waiting for its write confirmation; `Frame.Assembler` joins the answer's pieces. Bytes it has to drop (a piece
  that runs past a message's end, or one without the start byte while no message is open) are handed on too
  (`Assembler.take`, never `Frame.whole`): the session stores them as they came (`WatchStore.addDamaged`, under the type of
  the data request running, -1 outside one), they give no rows, and that answer counts as cut off even when its end part
  came, so its start does not move past records that were never stored (review of dadbb71). The session goes on.
* **Session** (`core/withings/Session`, JVM tested against a made up watch): the hello, then a list of steps
  (`core/withings/WatchSetup` builds them: the first setup, each sync, the alarm, the hand calibration; see the watch setup
  below), and always "sync done" last while the link is up. Each request waits for its
  answer (5 s; a data answer 20 s between messages, until a message with the end part), at most 2 minutes in all. The
  watch asks for a sign in right after the hello; `core/withings/SignIn` answers it and the session goes on (see the sign in
  below). When the sign in comes as the answer to a request other than the hello, that request is not sent again: the
  session waits for its answer once more, and a watch that dropped it ends the session with "did not answer" (a known
  limit). The watch's own messages on the way are stored and skipped. Every message is stored as it arrives (`WatchStore.addRaw`), so an early end keeps what came.
* **Storage (schema 9, only adds; schema 10 adds `watch_moves`, see the watch setup below):** `watch_raw` (session start in ms, place in the session, device, type, the whole
  message, captured) keeps every message as it came, also two equal ones; `watch_devices`; and the rows worked out from
  the messages by `core/withings/Records` (`watch_steps`, `watch_hr`, `watch_sleep`, `watch_workouts`, `watch_battery`,
  keyed by device and time, UTC seconds), so the same message twice adds nothing. kv `watch_decoder` holds
  `Records.VERSION`; a new decoder works every row out again in one transaction before the next sync (with the watch lock,
  before the ring lock is taken, so a ring sync never waits for it), the old rows stay until then. kv
  `watch_decoded_session` notes the newest session decoded with all before it, in the transaction that stores its rows; a
  session whose decoding failed (a full disk) stays behind it and the next sync decodes it first (`WatchStore.decodePending`,
  oldest first, stopping at the first failure). Without the note (an older version, pairing another ring) every message is
  decoded again once. Each request (activity, movement, heart rate) starts at T, the newest record of its own last whole answers (one that
  reached the end part `0x0100`) minus 2 hours, a month back when it never had one (owner's report of 11 November: before,
  T came from the stored rows of any answer, so heart rates inside an activity answer moved the heart rate request on, and
  an answer cut off after another one was never asked again). T is noted in kv `watch_cursor_<type>_<address>` in the
  transaction that stores the session's rows, never moves back, and is worked out again from the raw messages
  (`WatchStore.fromRaw`, `Records.newestComplete`) when not noted: after an update, a new decoder, a restore or pairing
  another ring (which empties kv). Pairing another ring keeps the watch tables (`Db.KEPT_TABLES`), Delete all empties
  them, a restore carries the phone's raw messages and devices into the restored file like the period log (same step of
  `Db.replaceWith`, the phone's row wins, any failure puts the old file back) and works the rows out again. A damaged phone
  file loses its watch rows with its period log (the restore says so). Days come from the `zones` table
  (`WatchDays.zoneIn`, the rule of `Db.zoneAt`).
* **How often** (`core/withings/WatchDue`): by hand, once (about 07:00), twice (about 07:00 and 19:00) or 4 times a day
  (about 01:00, 07:00, 13:00, 19:00), default once, clock times in the phone's zone. The 3 hour `SyncJob` runs the ring sync
  first, then the watch when the newest of those times passed after its last good sync and no attempt was made since that
  time or in the last 2.5 hours. The job is scheduled when the ring's background sync is on or a watch syncs on its own.
  What one run does is decided by `core/BackgroundJob.due` (issue #329): nothing while the ring is busy, the ring part
  when background sync is on, the key can be read and the last sync or attempt is over 30 minutes ago (one that lies in the future, after the clock was set back, counts as not recent; a skipped ring part is logged), the
  watch part outside demo mode when it is due; `SyncJob` only reads the settings and asks it.
* **Screens:** Settings, Watch (the watch, paired with this phone or not, last sync, battery, last problem, "Sync the watch
  now", the chips, "Open the watch page", "Forget the watch": syncing stops, nothing is deleted); the
  Watch page with steps per day (30 days as dots) and per hour (touch readable, the hours add up to the day), the day's
  workouts, the heart rate curve, Withings' own sleep as a timeline (per minute, awake, REM, light, deep; time without a
  record or with a code not known as no data) with the watch's heart rate readings of the night under it on the same time
  axis (lowest and average of the readings; readings more than an hour apart are not joined) and the battery per day with its drop per day (falls added up,
  charges left out, over 7 days). Nothing on the page is stored: `core/withings/WatchDays` works it out on each load.
* **Log and Data check:** every step, message type and length, never a value (the JVM test checks the session's lines);
  the Data check names counts, dates and message types only (no step, heart rate or battery value).
* **Demo mode:** `core/withings/WatchDemo` makes up five weeks of messages, stored and decoded like a real watch's, in the
  demo file only. The demo's ring is on its charger yesterday 14:00 to 16:00 (`Demo.charging`), so the watch fills that time.
* **Main device, one set of numbers** (`core/MainDevice`, the owner's decision of 3 November): Settings, Watch,
  Main device (Ring, Watch or Phone, only the devices present, `Prefs.mainDevice`, in the backup; stored codes 0 ring, 1 watch,
  2 phone, and a backup with any other value keeps the phone's choice). Nothing stored: the ring when one is paired, else the
  watch, else the phone (`MainDevice.defaultMain`); a stored 2 is the phone only while the phone counts steps, otherwise the ring
  (`MainDevice.shown`). With steps from this phone the chips live in Settings, Phone (the Watch card names the main device). Each device is one `MainDevice.Source` (the quarter hours it covers, its own day total,
  the steps it lends per quarter hour, its heart rate), and `MainDevice.numbers` is the one step sum and gap filling for
  any main device (issue #329, phase 1a; the same days as before, JVM test). The other
  devices only fill the quarter hours the main one was not worn, in the order ring, watch, phone, each only where no device
  before it was worn (phase 1b; `Source.estimate` rounds and `Source.ownDay` picks the other device's own day when the main one
  has none, two flags since the review of #347). Ring worn: its day summary has movement there
  (`met_profile`, by clock time), a heart rate point (`hr_day`) or one of its nights or naps covers it; so charging, off the
  finger and a left out time (#254) count as not worn. Watch worn: any of its records within 30 minutes (it has no wear
  signal and measures heart rate about every 30 minutes). Filled: steps and distance (main ring: the watch's step records
  that start in the ring's gaps; main watch: the ring's estimate per quarter hour from its movement, the per minute rule of
  `Wellness.day`), the heart rate line of Body (the other device's readings as quarter hour averages), the night when the
  main device has none (a watch night is Withings' own stages, `WatchDays.nightFor`, shown on Sleep without a score). The
  watch's workouts are listed on Activity. A day the main device has nothing on takes the other's whole day. Each filled part
  gets one line naming the device and the clock times. Worked out on each day load (`DayData.merge`, `WatchStore.window`,
  `WatchStore.ringDay`); nothing is stored or analysed again. Readiness, the sleep score, HRV, breathing, blood oxygen,
  temperature, burn, movement minutes and the 7 and 30 day charts stay the ring's. With only a ring every number is the
  stored one, byte for byte (JVM test).
* **Statistics without a ring** (`core/DeviceHistory`, `ui/HistoryViews`, `data/WatchHistory`; owner's report of 13 November):
  with only the watch (or only the phone) each tab gets the statistics a ring has, worked out on each day load from the stored
  rows (`DeviceHistory.range`: 30 days before the shown day, the strip's oldest night, to 30 after it, at most today, plus the 35
  days the scores read; the sleep pieces of 103 nights before the first day for sleep debt), nothing stored. Each day's scores are
  `DeviceScores.score`, the function of the dials: the day itself with its rows up to its own midnight and in its own zone
  (`DeviceHistory.DayZones`, the zone noted at its noon as the dial has it, so a trip does not shift other days' borders), as the
  day load reads them (JVM test: every day equals `DeviceScores.of` on the day load's rows, a week in another zone included; the
  dials' fingerprint is the one from before).
  Sleep: the recent nights strip, the night timeline, Details (time in bed, efficiency, latency when the watch marked one, wake ups),
  sleep debt (the ring's rule on the watch's nights: 90 nights before each of the 14 days), total sleep of 7 days, sleep times and the sleep score of 30 days, Further
  analysis of the watch's nights (`WatchHistory.load`, sleep and heart rate rows only, each night in its day's zone, the heart rates
  sorted once for All time, `SleepHistory.of`). Ready: last night's
  lowest reading against the usual of 14 nights, resting heart rate of 30 days, readiness of 7 days. Activity: steps of 7 and 30
  days, the activity score of 7 days, the watch's calories of 7 days and workouts of 30 days. Body: resting and day heart rate of
  30 days, cardio capacity (Uth method on the watch's lowest reading). One "Needs a ring" card per tab names what stays the ring's.
  The 7 day bar charts read by touch (`BarChart.tips`; the ring's own do not, unchanged). With a ring none of this shows.
* **REM** (owner's report of 13 November: the Steel HR sends awake, light and deep only): decided per night (`WatchDays.Rem`,
  code review of #389). Each watch that ever sent a REM record tells REM from its first REM record to the end of its last sleep
  piece (`WatchStore.rem`, per `device` of `watch_sleep`); a night tells REM when it reaches into such a span. So a Steel HR's
  nights keep Light and REM whether it was worn before or after a watch with REM. Without, a watch night shows "Light and REM" as
  one stage (Hypnogram and Pattern with `lightRem`, one stage row, the Watch page), the sleep score's stage source is
  `Scores.NO_REM`, which leaves the REM part out (its weight shared out, the Left out card says why), and Further analysis shows
  Deep and Light and REM when any night of the period does not tell REM. Ring nights never pass
  through this (JVM test: the ring's 9,000 seeded scores unchanged).

Protocol facts, in our own words, not confirmed with a real watch yet. Sources: the public pairing recording and notes in
Gadgetbridge issue 854 and pull request 2831 on Codeberg, the Gadgetbridge Withings device page, and the message and part
numbers, field layouts and order read in Gadgetbridge's Withings sources (AGPL; facts only, no code copied or translated).
The owner's Watch check confirmed the service and its characteristics (`0x21`, `0x23`, `0x24` write and notify, `0x22`
read, no Device Information or Battery service, silent until asked).

* Service `00000020-5749-5448-0037-000000000000` ("WITH" in ASCII; the older Activité uses `0034`). Requests and answers
  go through `00000024-…`.
* A message: `01`, type (2 bytes), length of the rest (2 bytes), then parts: type (2), length (2), value. Big endian.
  Messages the watch starts itself have `0x40` or `0x80` in the top byte of the type (masked off when reading it).
* Hello `0x0101` with part `0x012a` (platform 1, app 1, app version `0x003d7e96`, the value of the public recording) and
  part `0x0928` (Android version). The answer's part `0x0101`: a number, then texts with a length byte first (name, address,
  another text), a number, a model text, a number, the firmware number, a number. Our hello is byte for byte the public
  recording's (a JVM test).
* Connect `0x0111` (empty). Battery `0x0504`, answer part `0x0504`: percent, state (0 charging, 1 low, other normal),
  voltage (4 bytes). Set time `0x0501`, part `0x0501`: now, the zone's offset from UTC, the next clock change and the offset
  after it (4 bytes each). Sync done `0x0115` (empty); the notes say the watch stops answering when a session ends without it.
* Activity records `0x0978` and heart rate records `0x0928`, each with part `0x0506` (from, 4 bytes; count, 2 bytes, 0 for
  all), heart rate also with part `0x0961` (version 1). The answers come as several messages until one holds the end part
  `0x0100`. Records follow each other, each starting with a time part `0x0601` (unix seconds): duration `0x0602` (seconds),
  steps and distance `0x0603` (steps 2 bytes, distance 4 in centimetres, then climbed and descended), calories `0x0608` and `0x060a` (2
  bytes, then an intensity), sleep stage `0x0607` (0 awake, 1 light, 2 deep, 3 REM: the numbers of Withings' public sleep data, which the owner's first real night confirmed with 1 as the most common code; Gadgetbridge reads 0 and 1 as light), workout type `0x0969` (codes:
  1 walking, 2 running, 3 hiking, 6 cycling, 7 swimming and 30 more), running `0x0605` and swimming `0x060d` marks, heart
  rate `0x0929` (the first byte). Sleep heart rate comes as records with only a heart rate.
* `0x0506` "movement samples" is asked too since the watch setup (below); its records go through the same decoder and are
  keyed by device and start, so a record in two answers counts once. Each answer may carry only some parts of a record
  (the movement answer may lack the calories part), so a later answer replaces a stored value only with a non zero one
  (`WatchStore.kept`): steps and calories of the activity answer and the climb of the movement answer end in one row.
* The watch drops a write that comes while it is busy, so each request waits for its answer.
* **Sign in** (`core/withings/SignIn`). The owner's watch (Steel HR, nightly 1.99, 3 November) answered our hello at once
  with a challenge instead of the hello answer: message `0x0128`, 44 bytes, one part `0x0122`. So the sign in is needed for
  every sync. Facts (the owner decided on 3 November to use the public secret as a protocol fact, see NOTICE):
  * Part `0x0122` (challenge): a length byte and the watch's Bluetooth address as text (`XX:XX:XX:XX:XX:XX`, 17
    characters), then a length byte and 16 random bytes. That is 4 + 35 bytes, exactly the 44 byte message seen.
  * The proof is SHA-1 over the challenge bytes, then the address text as the watch sent it, then the fixed secret
    `2EM5zNP37QzM00hmP6BFTD92nG15XwNd` (32 ASCII characters), simply one after the other.
  * The answer is again message `0x0128` with two parts: `0x0123` (a length byte, 20, then the proof) and `0x0122` with
    the watch's address text and 16 random bytes of our own (69 bytes in all with a 17 character address).
  * The watch then sends the answer to the hello (`0x0101`), which may hold part `0x0123` with its own proof for our
    challenge (SHA-1 over our bytes, the address and the same secret). When it is there and wrong, the session ends ("The
    watch answered the sign in wrongly"); when it is missing, the session goes on, as Gadgetbridge does.
  * What a refusal looks like is not documented. The session counts as refused (plain sentence "The watch did not accept the
    sign in") when, after our answer, the hello answer does not come within 5 s, the watch asks again, or it closes the
    link. A challenge of another layout ends the session as before ("a sign in of a kind this version does not know").
  * Sources: Gadgetbridge's Withings Steel HR support (AGPL), files `AuthenticationHandler.java` (the secret, what is
    hashed in which order, our own challenge back, the check of the watch's proof), `Challenge.java` and
    `ChallengeResponse.java` (the part layouts), `WithingsStructureType.java` (parts 290 = `0x0122`, 291 = `0x0123`) and
    `WithingsMessageType.java` (296 = `0x0128`), read on the GitHub mirror of `Freeyourgadget/Gadgetbridge` (master, 3
    November 2026). Gadgetbridge answers the challenge after its hello on every connection that is not the first pairing,
    then syncs. No code was copied or translated; `SignIn` is our own. No public example pair (challenge with its proof)
    was found, so the JVM test checks against values worked out outside this repository.
  * The log names only that a sign in was answered, its length, whether the address in the challenge is the connected one,
    and whether the watch's proof was right; never the address, the bytes or the secret. The challenge itself is stored in
    `watch_raw` like every message from the watch (it holds no health value); our answer is not stored.
* **Watch setup and full sync** (`core/withings/WatchSetup`, `ui/WatchSetupActivity`, the owner's report of 4 November: the
  watch signed in but only the battery came). Facts read in Gadgetbridge's Steel HR support (AGPL, GitHub mirror
  `Freeyourgadget/Gadgetbridge`, master, files `WithingsSteelHRDeviceSupport.java`, `WithingsMessageType.java`,
  `WithingsStructureType.java`, `UserUnitConstants.java`, the data structures `User`, `UserUnit`, `ActivityTarget`, `Locale`,
  `Time`, `ScreenSettings`, `GetActivitySamples`, `TypeVersion`, `ActivitySample*`, `AlarmSettings`, `AlarmStatus`,
  `MoveHand`, `EndOfTransmission`, the handlers `ActivitySampleHandler`, `SyncRequestHandler`, `SetupFinishedHandler`, and
  `WithingsCalibrationActivity`; read 4 November 2026). Facts only, in our own words; no code copied or translated.
  * **First setup** (Gadgetbridge's first connection): connect `0x0111`, language `0x011a` (part `0x0121`: a length byte
    and two letters; the watch knows de, en, es, fr, it), start and stop of the hand calibration, set time, distance unit and
    clock `0x0112` (part `0x0119`: two zero bytes, what (2 distance, 4 clock), the value (24 km, 25 miles, 26 for 24 hours,
    27 for 12 hours), 2 bytes each), the daily step goal `0x050a` (part `0x0511`, 8 bytes), notifications on (`0x0929` with
    part `0x092a`, 1 byte; needs the phone to serve the notification service, not sent here), battery, the display screens
    `0x050c` and "setup finished" `0x0113`, answered with the same type. We send the same, without the calibration and the
    notifications, plus the user (below) and the alarm, then the first sync's data requests in the same connection.
  * **The display screens** `0x050c`: one part `0x0516` per screen (id 4 bytes, a user number 4, two zero numbers 4 each, its
    place 1 byte, then 1), the end part `0x0100` last. The 7 screens Gadgetbridge sends (id, place): `0xff` 6, `0x3d` 1,
    `0x33` 4, `0x2d` 2, `0x2a` 3, `0x26` 7, `0x39` 9. Which id is which screen is not documented.
  * **Each sync** (Gadgetbridge, after the sign in): connect, battery, set time, the user `0x0502`, the step goal, the two
    units, some reads of the alarm and screens (not sent here), then three data requests, each until the end part: activity
    `0x0978` (part `0x0506`: from, unix seconds 4 bytes, count 2 bytes, 0 for all; no version part), movement `0x0506` (the
    same, plus part `0x0961` version 1) and heart rate `0x0928` (the same as movement), then sync done `0x0115` ("must be
    sent in all cases"). Gadgetbridge's "from" is the last sync time, the first time now minus 10 hours; we ask from the newest
    stored record of the kind minus 2 hours, and 30 days back the first time. Nothing says the watch drops records once they
    are sent or that an answer must be acknowledged; both requests ask "since a time".
  * **The user** `0x0502`, part `0x0503`: a user number (4 bytes; any number, Gadgetbridge sends 123456), weight (4, whole
    kg), height (4, whole cm), sex (1 byte: 0 male, 1 female), birth date (4, unix seconds), then the name with a length byte
    (we send none). The app's profile has an age, not a birth date: we send 1 July of the year it points to. A profile without a
    sex sends no user until the setup screen asks for it.
  * **Records** (answers of all three requests): a time part `0x0601` starts each record; duration `0x0602` (2 bytes);
    movement `0x0603` (steps 2, distance 4, climbed 4, descended 4); walking `0x0604` (a level, 2 bytes), running `0x0605`,
    swimming `0x060d` (marks); sleep `0x0607` (2 bytes: 0 awake, 1 light, 2 deep, 3 REM); calories `0x0608` and `0x060a` (calories 2,
    then an intensity 2); `0x060b` unknown; workout type `0x0969` (2 bytes); heart rate `0x0929` (1 byte). Decoder version 2
    stores the intensity and the climb in `watch_moves`. Decoder version 3 (owner's report of 11 November) reads the distance
    as centimetres and stores whole metres (half a metre up): the owner's first day came to about 80 per step, a step's length
    in centimetres. Gadgetbridge stores the number without a unit. The climb's unit is not confirmed (shown as metres).
  * **The watch's own messages** have `0x41` or `0x81` as the type's first byte. A sync ask `0x0141` from the watch is
    answered with an empty `0x4141` (the answer to one of the watch's own messages carries `0x4000`), as Gadgetbridge does.
    Live workout messages (`0x013d`, `0x013e`, `0x0140`) and notification requests are only stored here.
  * **Alarm** `0x0145`, part `0x0512`: hour, minute, days (Sunday `0x01`, Monday `0x02` up to Saturday `0x40`, always plus
    `0x80`), day of month, month and year (0 for a repeating alarm), the smart wake window in minutes (Gadgetbridge always 15),
    one byte each; an optional name part `0x0514`. Then `0x091b` with part `0x0919` (1 byte, 1 on, 0 off); off alone switches
    it off. Gadgetbridge offers 3 alarm slots, but whether a second `0x0145` adds or replaces is not documented, so the app has
    one alarm. It is sent from the setup screen or with the next sync after a change; a change made while a session sends the
    older one stays pending (a change counter, `Prefs.watchAlarmChange`). On without any day is sent as off.
  * **Hands** `0x011e` starts the calibration (the hands go to their home), `0x011c` with part `0x050c` moves one: the hand
    (1 byte: 0 minutes, 1 hours, 2 the activity hand) and the amount (2 bytes, signed, plus is clockwise), `0x011f` ends it.
    Gadgetbridge's guide: hour hand to 12, minute hand to 12, activity hand to 100 % (it only moves clockwise). The size of one
    step and whether a move is answered are not documented: our session waits 1.5 s for an answer and goes on without. The
    end (off, then set time) is also sent when the session ends early while the link is up (`Session.Plan.last`). Its time
    is made when it is sent (`Session.Plan.clock` and `zone`), not when the session began, since a calibration can last
    5 minutes. When the link is lost before the end, Prefs `watch_calibrating` stays set and the next session of any kind
    sends calibration off and the time right after connect (`WatchSetup.closeCalibrationFirst`).
  * **Time and zone:** set time carries now, the offset now, the next clock change and the offset after it, so the hands
    follow a daylight saving change without a sync; every sync and the end of a calibration send it again.
  * **Not built (a later phase):** phone notifications on the watch (the phone must serve Apple's notification service),
    workout screens with icons (`0x013c`, image parts), live heart rate (`0x0927`), the heart rate measuring interval and
    the wrist side (no message known in Gadgetbridge), more than one alarm.
  * **Why the owner's sync brought only the battery** is not known without his log. Missing were: the whole setup (no user,
    no "setup finished"), the movement request, a positive first "since"; and removing the watch from the Withings app likely
    reset it, so it may have held nothing yet. The log now names, per data answer, the part types and how often each came.
  * **The flow** (`ui/WatchSetupActivity`, Settings, Watch, Set up the watch): find (a 10 s scan, Withings watches first),
    pair (Android's bond, its dialog), sign in, send the setup (the screen lists every value sent; a missing sex or a profile
    never entered leads to Settings, Profile, whose save re analyses the history like any profile change), the first sync,
    done; each with its state and Retry. A watch with another address than the saved one is never taken as set up.
    Below: the alarm and the hand calibration. All sessions go through `WatchSync` with the ring's rules (the ring always wins).
* Heart rate outside workouts seems to be one reading every 30 minutes by default, continuous during workouts.
* Unknown: whether the watch drops records once one app synced them (requests ask "since a time", which suggests it keeps
  them for both apps), whether a workout's steps are also in the normal records, and the distance unit (shown as metres).

## Steps from this phone (issue #329, phase 1b)

Open Ring is useful without a ring or a watch: screen time and the period tracker were phone only already, and the phone's own
step counter (`Sensor.TYPE_STEP_COUNTER`, the steps since the phone started) adds steps. Android's documentation says the
counter counts only while the sensor is activated, and since Android 9 an app idle in the background gets no sensor events;
on many phones the counter runs all the time in a low power chip, on others only while some app (often the phone's health
app) keeps it on. Neither can be checked on the JVM; the log names each reading's source and whether a value came.

* **Reading** (`sync/PhoneReader`): register for the sensor, wait at most 5 s for its first value (`flush` brings a held back
  batch), unregister. No value: nothing is stored, `Prefs.notePhoneNoReading` counts it for the Data check. The reading is the
  counter, `SystemClock.elapsedRealtime`, the wall clock and `Settings.Global.BOOT_COUNT` (`PhoneSteps.bootKey`: a stand in from
  the start time if it cannot be read). One reading at a time. When: `sync/PhoneJob` (id 4714, every 60 minutes with 15 minutes
  flex, persisted, no battery condition, no network, no Bluetooth, never the ring's `op` lock; `BackgroundJob.due`'s phone part,
  which a busy ring never skips; scheduled only while the phone counts steps, `BackgroundJob.phoneWanted`), when the main screen
  resumes (skipped when the last reading of this boot is under a minute old), right after Physical activity is allowed, and in
  `sync/PhoneStepsReceiver` at `BOOT_COMPLETED` (and `ACTION_SHUTDOWN`, which Android 9 and newer send only to receivers
  registered at run time, so on most phones the reading after the start is the one that counts). The permission is
  ACTIVITY_RECOGNITION ("Physical activity", Android 10 and newer), asked on Activity; `uses-feature ... stepcounter` is not
  required, so phones without the sensor install and say "This phone has no step counter."
* **Storage** (`data/PhoneStore`, schema 11, only adds): `phone_readings` raw, only ever added to, keyed by `device` (a random
  id made once on the phone, `Prefs.phoneDevice`, never in a backup, so another phone's readings from a restore are never mixed
  with this phone's), `boot` and `elapsed`; `phone_steps` worked out by `core/PhoneSteps.intervals` per device: the very first
  reading of a phone is a baseline without an interval (its counter may hold weeks of steps from before), a changed boot count is
  a restart, and the first reading after a restart that was seen (an earlier boot of the same phone read before this one started,
  both with real boot counts) counts from the start's time, if the counter fits that time; intervals are ordered and measured by (device, boot, elapsed) and end at their second reading's wall time,
  so a clock set back or forward moves an interval but never stretches, shrinks or drops it; a counter that falls without a new
  boot count, or rises faster than 4 steps a second, gives no interval (counted for the Data check). A new reading works out its
  phone's intervals again, a new rule (`PhoneSteps.RULE`, kv `phone_rule`) all of them, each in one transaction; the schema step
  forgets the rule, so an upgrade stopped half way gets its intervals back at the next day load (`PhoneStore.forDay`, not while
  an analysis runs). The app opening reads only when this boot's newest reading is a minute old or more, before the sensor is on. Readings always go
  to the real file: `PhoneStore.record` holds Db's class lock (the lock of the restore's swap) and uses `Db.real`, which in demo
  mode is a second helper on the real file, taken over by `Db.get` when demo mode ends and closed before a restore; it writes
  nothing while an analysis runs (its transaction would hold the write for minutes), the next reading loses nothing.
* **Restore**: like the period log, `Db.replaceWith` reads the phone's readings before the swap and adds those the restored
  file lacks (insert, never replace), then works every interval out again, all in one transaction before `.old` goes; any
  failure puts the old file back. Older backups get the tables empty first. Delete all empties both; pairing another ring keeps
  them.
* **Days**: each day from its midnight to the next in its zone (`Json.dayZone`, as every screen), each interval's steps spread
  evenly over it (`PhoneSteps.share`), so a 23 or 25 hour day and a trip get their own hours. `Db.latestDayWithPhone` (the main screen's
  following only) also takes the day of the newest interval, so a person without a ring follows today; `Db.latestDay` stays the
  ring's newest day for the Settings preview and the Data check. After midnight a phone reading moves the main screen to the
  new day before the morning sync.
* **Main device**: the phone is a `MainDevice.Source`: it covers the quarter hours its intervals reach, lends each quarter hour's
  share (rounded to whole steps, the distance from the steps), has no heart rate and no night. With the ring or the watch as main
  device it fills only where neither was worn, with the usual line ("Includes about 840 steps from your phone, 14:00 to 15:00,
  when your ring was not worn."). `DayData.merge` passes the phone while it counts steps or has any reading.
* **Screens**: Activity shows the card "Steps from this phone" that asks for Physical activity in context (one button), after a
  refusal one line with Allow (the app's settings); allowed, the evidence: the main device's steps per hour and the phone's
  intervals as light bars below (higher when more steps a minute), touch readable ("14:02 to 15:04, 412 steps from your phone",
  or "about 206 steps (spread over 23:10 to 01:20)" for an interval split by midnight). Settings has a third block, Phone (on, or
  why not, the last reading, the main device chips). Setup's first screen has "Use without a ring"; Sleep and Ready without a
  ring, a watch and any night show one card ("Sleep and readiness need a ring or a watch. Add one in Settings.").
* **Data check**: readings, phone starts, intervals, gaps over 2 hours, what was left out, sensor values that did not come, and
  the day totals of the last 14 days, to hold next to the phone's own health app (numbers and dates only).
* **Demo**: `PhoneSteps.demo` makes up hourly readings of a phone carried by day with one restart, stored in the demo file
  (`Demo.phone`); a reading that arrives meanwhile goes to the real file.
* **What it cannot do**: no steps from before the first reading, none while the phone is not carried, and the steps since the last
  reading are lost when the phone dies without shutting down. Phone and ring count differently; phase 2 compares them.

## Known limits

* Scores are close to Oura's but not identical (Oura's tables and models are not public).
* Steps are estimated from activity intensity.
* Not ported: workout detection, cycle insights, cardiovascular age, firmware updates.

## Credits

Protocol, decoders and algorithms are ported from [open_oura](https://github.com/Th0rgal/open_oura) and
[open_health](https://github.com/Th0rgal/open_health) (MIT, Thomas Marchand), cross checked with the
[ringverse Ring 4 notes](https://github.com/ringverse/protocol/blob/main/oura/BLE.md),
[OpenStrap](https://github.com/OpenStrap/protocol), [BrightOura](https://github.com/gi-os/BrightOura)
and the Gadgetbridge Ring 4 work. The Withings notes above are protocol facts from public discussions, written in our
own words; no code was copied.
