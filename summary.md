## Test results

**6686 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 95% (1624/1709) | 85% |
| `core.analysis` | 99% (3044/3070) | 92% |
| `data` | 97% (2622/2700) | 86% |
| **core and data** | **97%** (7290/7479) | **89%** |

Not counted: about 9614 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 43% of all app code.
