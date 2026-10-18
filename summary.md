## Test results

**550 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 88% (718/819) | 69% |
| `core.analysis` | 94% (1269/1350) | 82% |
| `data` | 91% (1570/1731) | 72% |
| **core and data** | **91%** (3557/3900) | **75%** |

Not counted: about 6655 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 34% of all app code.
