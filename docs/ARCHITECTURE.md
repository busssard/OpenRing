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
| `core/` | Pure Java, no Android imports, unit tested on the JVM. `Protocol` (request bytes), `Packet` (framing), `Auth` (key handshake), `RingClient` (requests, history drain, live parsing), `HistoryBatch` and `ExtEnvelopeParser` (history batches), `EventCodec` (event decoders), `TimeLine` (ring counter to wall clock), `Tilt` (games), `Hex` |
| `core/analysis/` | `NightFinder` (where the sleeps are), `Stager` (sleep stages), `ScreenTime` (phone screen sessions, apps in front, their effect on a night), `ScreenScore` (screen score), `NightAnalyzer` (night metrics), `Scores` (Sleep, Readiness, Activity), `Wellness` (activity, calories, sleep debt, radar, VO2 max), `SleepPattern` (average night), `LiveHrv`, `Stats`, `TS` (time series) |
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
| `screen` | The phone's screen sessions (start, end), copied from Android's usage log, which keeps only about a week (version 3) |
| `apps` | When each app was in front (package, start, end), from the same log (version 4). Apps the person leaves out (Prefs) are subtracted from screen time; the sleep analysis ignores this list |
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
   nocturnal events. A period is never stretched into time the ring marks as awake. `Analyzer` then
   drops periods that read room temperature or an implausible heart rate (ring lying on a table).
   When "Count phone use as awake" is on (default), phone use of a minute or more that overlaps the first or
   last 10 minutes of a period moves its edge past it, and a period of under 15 minutes left is dropped.
2. `Stager` estimates stages per 30 seconds from heart rate, HRV and movement (open_health's model free
   stager). Ring 4 does not stage sleep itself. Sleep versus wake is reliable; deep and REM are estimates.
   Every 30 seconds with the phone screen on for at least 15 seconds becomes awake (`ScreenTime.markAwake`).
3. `NightAnalyzer` computes durations, efficiency, latency, wake ups, cycles, lowest heart rate, HRV,
   breathing, SpO2 and nightly skin temperature.
4. `Scores` combines contributors with the weights open_health recovered from Oura's engine (Sleep
   35/15/10/10/10/10/10); contributor curves are transparent and based on sleep research. Baselines become
   personal after about 14 nights.
5. `Analyzer` writes nights and days; it re analyses the last two days after each sync and everything
   when dating changed or the user asks (Settings, Help). "Everything" is the whole stored history with no
   age limit (a week at a time, so memory stays flat); with years of history that takes a few minutes.

## Threading

`RingManager` runs every ring task on one worker thread and guards the ring with one lock (`op`).
Anything that holds the ring (live, games) must release it when its screen pauses. Sync waits up to
15 seconds for the lock, live 10 seconds and games up to 3 minutes; Settings actions on the ring
(read or switch measurements, factory reset) fail at once with "The ring is busy". A sync releases the
lock as soon as its download ends, before it reads the screen log and analyses (issue #21); `busy()`
stays true until that analysis is done, so screens reload only then. Code that replaces or wipes the
database (restore, pairing a different ring) first waits for a running analysis (`synchronized
(Analyzer.class)`). Analysis and database reads in the UI run in AsyncTasks.

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
