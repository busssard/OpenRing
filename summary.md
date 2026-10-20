## Test results

**806 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 88% (759/858) | 73% |
| `core.analysis` | 97% (1457/1509) | 85% |
| `data` | 91% (1673/1830) | 75% |
| **core and data** | **93%** (3889/4197) | **78%** |

Not counted: about 6767 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 35% of all app code.
