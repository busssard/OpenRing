## Test results

**2671 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 92% (951/1037) | 81% |
| `core.analysis` | 99% (2937/2961) | 92% |
| `data` | 96% (2323/2410) | 85% |
| **core and data** | **97%** (6211/6408) | **88%** |

Not counted: about 8902 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 41% of all app code.
