## Test results

**514 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 94% (1243/1324) | 81% |
| `data` | 91% (1570/1731) | 72% |
| **core and data** | **91%** (3516/3863) | **75%** |

Not counted: about 6635 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 33% of all app code.
