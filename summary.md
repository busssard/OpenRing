## Test results

**1145 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (806/905) | 75% |
| `core.analysis` | 97% (1457/1509) | 85% |
| `data` | 91% (1673/1830) | 75% |
| **core and data** | **93%** (3936/4244) | **79%** |

Not counted: about 6773 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 36% of all app code.
