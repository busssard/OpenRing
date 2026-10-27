## Test results

**2398 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (913/1012) | 79% |
| `core.analysis` | 99% (2614/2640) | 91% |
| `data` | 95% (2219/2342) | 82% |
| **core and data** | **96%** (5746/5994) | **86%** |

Not counted: about 8704 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 39% of all app code.
