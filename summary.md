## Test results

**7644 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 96% (2011/2099) | 87% |
| `core.analysis` | 99% (3362/3380) | 93% |
| `data` | 97% (3387/3477) | 86% |
| **core and data** | **98%** (8760/8956) | **89%** |

Not counted: about 12298 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 41% of all app code.
