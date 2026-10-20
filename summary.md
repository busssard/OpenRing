## Test results

**1254 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (806/905) | 75% |
| `core.analysis` | 97% (1653/1702) | 86% |
| `data` | 92% (1693/1844) | 75% |
| **core and data** | **93%** (4152/4451) | **80%** |

Not counted: about 6804 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 37% of all app code.
