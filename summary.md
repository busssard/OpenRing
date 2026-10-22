## Test results

**1624 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (810/909) | 75% |
| `core.analysis` | 98% (2352/2402) | 89% |
| `data` | 92% (1761/1908) | 77% |
| **core and data** | **94%** (4923/5219) | **83%** |

Not counted: about 8277 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 36% of all app code.
