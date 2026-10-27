## Test results

**2287 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (913/1012) | 79% |
| `core.analysis` | 98% (2476/2525) | 90% |
| `data` | 94% (2210/2342) | 82% |
| **core and data** | **95%** (5599/5879) | **85%** |

Not counted: about 8660 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 39% of all app code.
