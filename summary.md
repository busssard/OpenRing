## Test results

**2999 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 93% (1130/1217) | 83% |
| `core.analysis` | 99% (3035/3061) | 92% |
| `data` | 96% (2410/2498) | 85% |
| **core and data** | **97%** (6575/6776) | **88%** |

Not counted: about 9383 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 41% of all app code.
