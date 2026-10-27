## Test results

**2245 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (897/996) | 79% |
| `core.analysis` | 98% (2476/2525) | 89% |
| `data` | 94% (2210/2342) | 82% |
| **core and data** | **95%** (5583/5863) | **85%** |

Not counted: about 8652 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 38% of all app code.
