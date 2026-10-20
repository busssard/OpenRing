## Test results

**1238 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (806/905) | 75% |
| `core.analysis` | 97% (1630/1682) | 86% |
| `data` | 92% (1694/1851) | 75% |
| **core and data** | **93%** (4130/4438) | **80%** |

Not counted: about 6787 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 37% of all app code.
