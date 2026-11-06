# Changes waiting for or included in a release

Every pull request with a change people will notice adds **one new file** here, instead of editing
`CHANGELOG.md` or a version number (that is what made every two open PRs conflict):

* name: `YYYY-MM-DD-short-name.md` (the date the PR was opened, then a few words), for example
  `2026-10-19-screen-clock-touch.md`
* content: one to five bullets written for the people using the app, plain language, no dashes as
  punctuation (they become the release notes as they are)

The version is counted, never typed: each file here (except this README) adds one to the version code
(`VERSION_CODE = 29 + files`, `tools/version.sh`). The name: stable releases are 2.0, 2.1, 2.2 (one up
for each release from `dev` into `main` that brings new files), and the nightly in between is 2.y.x, x
the files added since the stable 2.y (up to 1.102 the name was 1.(28 + files)). Merging a PR with a new
file here makes the next nightly. Files are never deleted or renamed; that
would change the count. A PR without a file here (docs, CI, a refactor nobody notices) publishes nothing.

Versions up to 1.28 are described in `CHANGELOG.md`.
