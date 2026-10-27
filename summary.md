## Test results

**2333 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (913/1012) | 79% |
| `core.analysis` | 98% (2564/2613) | 90% |
| `data` | 94% (2210/2342) | 82% |
| **core and data** | **95%** (5687/5967) | **85%** |

Not counted: about 8690 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 39% of all app code.
