## Test results

**1357 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 89% (806/905) | 75% |
| `core.analysis` | 97% (1864/1913) | 87% |
| `data` | 92% (1722/1872) | 75% |
| **core and data** | **94%** (4392/4690) | **81%** |

Not counted: about 7731 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 35% of all app code.
