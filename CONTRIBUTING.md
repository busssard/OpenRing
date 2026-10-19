# Contributing

Thanks for helping. Open Ring is a small hobby project; issues and pull requests are welcome.

## Reporting a problem or an idea

* Use the issue templates (bug or idea).
* For problems with the ring, attach the **Data check** (Settings, Help, *Data check and log*, copy).
  It holds counts and dates only. **Never post raw events, databases or screenshots of a real night**:
  that is health data.
* Mention your ring model and firmware (Settings, Ring) and your Android version.
* Security issues: please use a [private advisory](https://github.com/busssard/OpenRing/security/advisories/new),
  see [SECURITY.md](SECURITY.md).

Issues titled `[EXP] ...` (label `experiment`) come from an automated daily experiment run. They wait
for the owner's review; removing `[EXP]` from the title marks one as ready to be built.

## Changing code

Read [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) (build, test, release, common tasks) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (how it works). In short:

1. Branch from `main`, keep the change focused.
2. Run `./tests/run_tests.sh`, `./tests/run_pipeline.sh`, `./tests/check_privacy.sh` and
   `./tests/check_version.sh`.
3. For a change users will notice, add one note in `changes/` (see `changes/README.md`); the version is counted from them.
4. Open a pull request explaining what, why and how you tested; the template has a checklist. When the
   emulator test has run, look at the screenshots of your change.

Ground rules: no internet permission, no third party libraries or trackers, no real health data in the
repository, plain language in the app, and `core/` stays free of Android code.
