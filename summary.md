## Test results

**2188 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 90% (879/978) | 78% |
| `core.analysis` | 98% (2459/2508) | 89% |
| `data` | 94% (2123/2255) | 82% |
| **core and data** | **95%** (5461/5741) | **85%** |

Not counted: about 8627 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 38% of all app code.
