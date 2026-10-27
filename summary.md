## Test results

**2462 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (913/1012) | 79% |
| `core.analysis` | 99% (2678/2704) | 91% |
| `data` | 95% (2225/2348) | 82% |
| **core and data** | **96%** (5816/6064) | **86%** |

Not counted: about 8700 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 39% of all app code.
