## Test results

**11207 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 98% (4139/4240) | 91% |
| `core.analysis` | 100% (4341/4358) | 94% |
| `core.analysis.cycleforecast` | 99% (238/241) | 94% |
| `core.withings` | 100% (1264/1267) | 92% |
| `data` | 98% (4808/4909) | 87% |
| **core and data** | **99%** (14790/15015) | **91%** |

Not counted: about 19264 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 43% of all app code.
