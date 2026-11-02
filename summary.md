## Test results

**7259 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 96% (1920/2007) | 87% |
| `core.analysis` | 99% (3158/3184) | 92% |
| `data` | 97% (3340/3426) | 86% |
| **core and data** | **98%** (8418/8617) | **89%** |

Not counted: about 11268 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 42% of all app code.
