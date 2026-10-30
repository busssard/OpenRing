## Test results

**3428 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 94% (1263/1348) | 84% |
| `core.analysis` | 99% (3035/3061) | 92% |
| `data` | 97% (2474/2557) | 86% |
| **core and data** | **97%** (6772/6966) | **88%** |

Not counted: about 9604 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 41% of all app code.
