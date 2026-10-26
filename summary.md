## Test results

**1866 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (850/949) | 76% |
| `core.analysis` | 98% (2416/2466) | 89% |
| `data` | 93% (1903/2055) | 79% |
| **core and data** | **94%** (5169/5470) | **84%** |

Not counted: about 8422 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 37% of all app code.
