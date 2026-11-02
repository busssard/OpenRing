## Test results

**7464 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 96% (1997/2085) | 87% |
| `core.analysis` | 99% (3177/3195) | 93% |
| `data` | 97% (3365/3455) | 86% |
| **core and data** | **98%** (8539/8735) | **89%** |

Not counted: about 11664 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 42% of all app code.
