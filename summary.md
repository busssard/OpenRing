## Test results

**714 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 88% (718/819) | 69% |
| `core.analysis` | 96% (1412/1464) | 84% |
| `data` | 91% (1669/1826) | 75% |
| **core and data** | **92%** (3799/4109) | **78%** |

Not counted: about 6723 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 35% of all app code.
