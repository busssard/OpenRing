## Test results

**408 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 93% (1208/1297) | 80% |
| `data` | 90% (1489/1650) | 71% |
| **core and data** | **91%** (3400/3755) | **74%** |

Not counted: about 6381 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 34% of all app code.
