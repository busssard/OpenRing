## Test results

**503 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 94% (1243/1324) | 81% |
| `data` | 91% (1563/1724) | 72% |
| **core and data** | **91%** (3509/3856) | **75%** |

Not counted: about 6555 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 34% of all app code.
