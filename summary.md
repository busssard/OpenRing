## Test results

**1950 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (852/951) | 77% |
| `core.analysis` | 98% (2416/2466) | 89% |
| `data` | 93% (2031/2190) | 80% |
| **core and data** | **95%** (5299/5607) | **84%** |

Not counted: about 8452 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 38% of all app code.
