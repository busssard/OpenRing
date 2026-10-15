## Test results

**219 tests passed, 0 failed** (JVM unit and pipeline tests).

## Coverage of the code the JVM tests can run

| Package | Lines covered | Branches covered |
| --- | ---: | ---: |
| `core` | 87% (703/808) | 67% |
| `core.analysis` | 92% (1001/1089) | 78% |
| `data` | 88% (1063/1209) | 67% |
| **core and data** | **89%** (2767/3106) | **71%** |

Not counted: about 5570 lines in `ui/`, `ble/` and `sync/` need Android. The emulator test opens every
screen with demo data and fails on crashes; Bluetooth is tested with real rings. Counting those lines as
untested, the JVM tests reach about 32% of all app code.
