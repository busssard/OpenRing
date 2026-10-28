## Test results

**2645 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 92% (949/1035) | 81% |
| `core.analysis` | 99% (2937/2961) | 92% |
| `data` | 96% (2277/2363) | 85% |
| **core and data** | **97%** (6163/6359) | **88%** |

Not counted: about 8899 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 40% of all app code.
