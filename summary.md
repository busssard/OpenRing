## Test results

**1694 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (811/910) | 75% |
| `core.analysis` | 98% (2390/2440) | 89% |
| `data` | 92% (1761/1913) | 78% |
| **core and data** | **94%** (4962/5263) | **83%** |

Not counted: about 8284 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 37% of all app code.
