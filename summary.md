## Test results

**7034 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 95% (1742/1827) | 86% |
| `core.analysis` | 99% (3158/3184) | 92% |
| `data` | 97% (3204/3290) | 86% |
| **core and data** | **98%** (8104/8301) | **89%** |

Not counted: about 10548 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 43% of all app code.
