# Releasing

A release is a signed APK attached to a GitHub release. The source is the primary path — a
release is a convenience for anyone without a Termux build setup.

Work reaches `main` only through a pull request, and `main` requires both the PR and a passing
`build` check, so what ships is always a tree CI has built. `dev` is where work lands;
feature branches pull-request into `dev`, then `dev` pull-requests into `main`.

```bash
# 1. on dev: the version lives in AndroidManifest.xml - bump BOTH numbers there.
#    versionCode must increase every release, or Android refuses the update.
sed -i 's/versionCode="4"/versionCode="5"/; s/versionName="0.5.0"/versionName="0.6.0"/' AndroidManifest.xml

# 2. the changelog entry IS the release notes - write it above "## Unreleased"
$EDITOR CHANGELOG.md

# 3. open the release PR (dev into main) and wait for the build check, then merge it.
#    A merge commit, not a squash: a squash mints a new SHA and leaves dev needing a re-align
#    that a merge commit never needs.

# 4. build from the merged commit, in this clone, with the release key
git switch main && git pull --ff-only
git status --porcelain                 # must be empty - an APK from a dirty tree matches no source
KSPASS=$(cat ~/.ztrackpad-kspass) ./build.sh

# 5. prove the artefact before publishing it
aapt2 dump badging out/ztrackpad.apk | grep -E '^package|application-label:'
unzip -p out/ztrackpad.apk classes.dex | strings | grep -c 'com\.pi\.'    # must be 0
sha256sum out/ztrackpad.apk

# 6. tag the commit you built, and attach the APK with the notes from the changelog
git tag -a v0.6.0 -m 'So7o Z Trackpad 0.6.0' && git push origin v0.6.0
awk '/^## 0.6.0/{f=1;next} /^## /{f=0} f' CHANGELOG.md > "$TMPDIR/notes.md"
echo "sha256: $(sha256sum out/ztrackpad.apk | cut -d' ' -f1)" >> "$TMPDIR/notes.md"
gh release create v0.6.0 out/ztrackpad.apk --title 'So7o Z Trackpad 0.6.0' --notes-file "$TMPDIR/notes.md"
```

Rules that came from getting one of these wrong:

- **`versionCode` must increase**, and the tag should carry the same version as the changelog
  heading.
- **Build from a clean tree, at the commit you tag.** `git status --porcelain` has to be empty.
- **The release keystore never enters the repository.** It lives in this clone as
  `keystore.jks`, with its password in `~/.ztrackpad-kspass`. It is what lets an existing
  install update in place: lose it and no install of this app can ever update again. Back it up.
- **Put the SHA-256 in the release notes**, and say the APK is signed with the maintainer's key,
  so anyone holding a self-built copy understands why Android will not update across the two.
- **CI cannot see inside an APK that is attached to a release**, so the `strings` check in step 5
  is not optional even though the workflow runs its own copy of it on the tree.
