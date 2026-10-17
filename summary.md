## Test results

**361 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 93% (1208/1297) | 80% |
| `data` | 90% (1347/1501) | 71% |
| **core and data** | **90%** (3258/3606) | **74%** |

Not counted: about 6227 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 33% of all app code.
