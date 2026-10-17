## Test results

**356 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 93% (1205/1297) | 79% |
| `data` | 90% (1346/1498) | 70% |
| **core and data** | **90%** (3254/3603) | **73%** |

Not counted: about 6223 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 33% of all app code.
