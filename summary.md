## Test results

**2578 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (916/1015) | 79% |
| `core.analysis` | 99% (2749/2775) | 91% |
| `data` | 95% (2226/2349) | 82% |
| **core and data** | **96%** (5891/6139) | **86%** |

Not counted: about 8736 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 40% of all app code.
