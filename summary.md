## Test results

**2729 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 92% (929/1015) | 80% |
| `core.analysis` | 99% (2829/2855) | 92% |
| `data` | 96% (2270/2356) | 85% |
| **core and data** | **97%** (6028/6226) | **88%** |

Not counted: about 8740 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 40% of all app code.
