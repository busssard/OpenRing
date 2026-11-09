## Test results

**9762 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 97% (2766/2864) | 89% |
| `core.analysis` | 100% (4060/4077) | 93% |
| `core.withings` | 100% (1051/1054) | 91% |
| `data` | 98% (4220/4315) | 88% |
| **core and data** | **98%** (12097/12310) | **90%** |

Not counted: about 16604 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 42% of all app code.
