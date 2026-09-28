# Contributing

**Bug reports and ideas: please open an issue.** It is the fastest route, and nothing about this
repository's branches needs to match yours.

**Pull requests are welcome as patches**, with one wrinkle: this tree is **generated**. It is
exported from a development repository, and each push here is a single squashed commit. Two
consequences are worth knowing before you spend time on a change.

- **A merged PR would be overwritten.** The next export replaces every tracked file, so a merge
  here would survive in the history while vanishing from the tree — the history would then lie
  about what the app contains.
- So a PR is handled as a **patch**: it is applied to the development tree, built, and tested
  there (on a device, for anything a finger has to check), and it reaches this repository again
  through the normal export — with you credited in the commit and in [`CHANGELOG.md`](CHANGELOG.md).

Practical notes:

- **Branch from `main`** and keep the change small. A fix touching only
  `java/app/so7o/ztrackpad/` or a shell script is the easiest to port.
- **`build.sh` builds and signs locally.** It needs `KSPASS` or `~/.ztrackpad-kspass`; the
  release keystore is not in this repository. Your own throwaway key is fine for testing.
- **Do not re-run the rebranding.** The package id, the label and the paths here are all
  deliberate, and each one differs from the development tree.
- **Do not add new files that name a development-tree path or id.** Write them so they need no
  renaming, the way `tests/smoke.sh` discovers the package id instead of hardcoding it.
- **Expect a PR to stay open until a release carries it**, and to be closed with a version number
  rather than a merge commit.
