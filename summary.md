## Test results

**271 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 92% (1078/1166) | 78% |
| `data` | 89% (1167/1318) | 68% |
| **core and data** | **90%** (2948/3292) | **72%** |

Not counted: about 5858 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 32% of all app code.
