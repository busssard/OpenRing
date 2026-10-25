## Test results

**1756 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (829/928) | 75% |
| `core.analysis` | 98% (2405/2455) | 89% |
| `data` | 92% (1794/1946) | 79% |
| **core and data** | **94%** (5028/5329) | **83%** |

Not counted: about 8349 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 37% of all app code.
