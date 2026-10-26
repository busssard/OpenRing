## Test results

**1995 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (852/951) | 77% |
| `core.analysis` | 98% (2417/2466) | 89% |
| `data` | 94% (2084/2227) | 81% |
| **core and data** | **95%** (5353/5644) | **84%** |

Not counted: about 8464 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 38% of all app code.
