## Test results

**7442 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 96% (1983/2071) | 87% |
| `core.analysis` | 99% (3169/3187) | 93% |
| `data` | 97% (3363/3453) | 86% |
| **core and data** | **98%** (8515/8711) | **89%** |

Not counted: about 11641 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 42% of all app code.
