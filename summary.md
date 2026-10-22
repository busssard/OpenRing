## Test results

**1529 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (810/909) | 75% |
| `core.analysis` | 98% (2174/2223) | 88% |
| `data` | 92% (1725/1875) | 76% |
| **core and data** | **94%** (4709/5007) | **82%** |

Not counted: about 8210 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 36% of all app code.
