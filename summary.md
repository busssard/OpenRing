## Test results

**607 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 88% (718/819) | 69% |
| `core.analysis` | 94% (1293/1374) | 82% |
| `data` | 91% (1657/1814) | 74% |
| **core and data** | **92%** (3668/4007) | **76%** |

Not counted: about 6710 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 34% of all app code.
