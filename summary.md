## Test results

**2605 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (916/1015) | 79% |
| `core.analysis` | 99% (2756/2782) | 91% |
| `data` | 95% (2228/2351) | 82% |
| **core and data** | **96%** (5900/6148) | **86%** |

Not counted: about 8740 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 40% of all app code.
