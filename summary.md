## Test results

**2054 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (864/963) | 77% |
| `core.analysis` | 98% (2459/2508) | 89% |
| `data` | 94% (2096/2234) | 81% |
| **core and data** | **95%** (5419/5705) | **84%** |

Not counted: about 8548 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 38% of all app code.
