## Test results

**2519 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (915/1014) | 79% |
| `core.analysis` | 99% (2737/2763) | 91% |
| `data` | 95% (2225/2348) | 82% |
| **core and data** | **96%** (5877/6125) | **86%** |

Not counted: about 8732 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 40% of all app code.
