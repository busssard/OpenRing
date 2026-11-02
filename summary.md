## Test results

**7159 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 96% (1919/2006) | 87% |
| `core.analysis` | 99% (3158/3184) | 92% |
| `data` | 97% (3205/3293) | 86% |
| **core and data** | **98%** (8282/8483) | **89%** |

Not counted: about 11243 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 42% of all app code.
