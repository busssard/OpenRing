## Test results

**3312 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 94% (1215/1299) | 83% |
| `core.analysis` | 99% (3035/3061) | 92% |
| `data` | 97% (2435/2518) | 85% |
| **core and data** | **97%** (6685/6878) | **88%** |

Not counted: about 9521 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 41% of all app code.
