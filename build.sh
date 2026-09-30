#!/usr/bin/env bash
# Hand-rolled Android build. Written and run in Termux/aarch64 (a Galaxy Z Fold 4); needs
# only aapt2, aidl, javac, d8, apksigner and python3, so any host with those will do.
# aapt2 (compile+link) -> aidl -> javac -> d8 -> package -> apksigner
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="$ROOT/sdk"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
BUILD="$ROOT/build"
OUT="$ROOT/out"
KS="$ROOT/keystore.jks"
# Signing key password.
#
# Deliberately NOT stored in this file, because this file is committed: a hardcoded
# password here is public the moment the keystore leaks (a home-dir zip, a device
# backup), and there would be no second factor to fall back on. Provide it either in
# the environment or in a file outside the repo:
#
#   KSPASS=... ./build.sh
#   printf %s 'the-password' > ~/.ztrackpad-kspass && chmod 600 ~/.ztrackpad-kspass
#
KSFILE="$HOME/.ztrackpad-kspass"
if [ -z "${KSPASS:-}" ] && [ -f "$KSFILE" ]; then
  KSPASS="$(cat "$KSFILE")"
fi
KSPASS="${KSPASS:?set KSPASS in the environment, or create $KSFILE containing it}"
MIN_SDK=30
TARGET_SDK=36
# versionCode/versionName live in the manifest only, so a release cannot be built with a label
# that disagrees with the tree it came from.
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] \
  || { echo "no versionCode/versionName in AndroidManifest.xml"; exit 1; }
LIBS="$ROOT/libs"
CP="$ANDROID_JAR:$(ls "$LIBS"/*.jar | paste -sd: -)"

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/gen" "$BUILD/dex" "$OUT"

echo "==> 1/6 aapt2 compile"
aapt2 compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "==> 2/6 aapt2 link"
aapt2 link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$BUILD/res.zip"

echo "==> 2b/6 aidl"
aidl -I"$ROOT/aidl" -o"$BUILD/gen" "$ROOT/aidl/app/so7o/ztrackpad/IShellService.aidl"
ls "$BUILD/gen/app/so7o/ztrackpad/"

echo "==> 3/6 javac"
find "$ROOT/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -cp "$CP" \
  -encoding UTF-8 \
  -nowarn \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt" 2>&1 | grep -viE "bootstrap class path|source value 8|target value 8|deprecat" || true
[ -d "$BUILD/classes/app/so7o/ztrackpad" ] || { echo "javac produced no classes"; exit 1; }

echo "==> 4/6 d8"
{ find "$BUILD/classes" -name '*.class'; ls "$LIBS"/*.jar; } > "$BUILD/inputs.txt"
d8 --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" @"$BUILD/inputs.txt"

echo "==> 5/6 package"
# Pack the APK ourselves rather than `cp` + `zip`, because an uncompressed entry has to start
# on a 4-byte boundary and nothing here provides zipalign. Where aapt2 stored a file (it keeps
# small PNGs uncompressed) the offset is padded with an extra-field block readers ignore, so
# the alignment check below is a guarantee instead of a coincidence.
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$OUT/ztrackpad-unsigned.apk" <<'PY'
import sys, zipfile, struct

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
STAMP = (2008, 1, 1, 0, 0, 0)   # fixed mtime, so one tree gives one APK
PAD_ID = 0xD935                 # zipalign's alignment-padding extra field

def entries(base):
    with zipfile.ZipFile(base) as src:
        for info in src.infolist():
            yield info, src.read(info.filename)

with open(out, "wb") as f:
    dst = zipfile.ZipFile(f, "w")
    items = list(entries(base)) + [(None, open(dex, "rb").read())]
    for info, data in items:
        name = info.filename if info else "classes.dex"
        stored = bool(info) and info.compress_type == zipfile.ZIP_STORED
        zi = zipfile.ZipInfo(name, info.date_time if info else STAMP)
        zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        zi.external_attr = info.external_attr if info else 0o644 << 16
        if stored:
            zm = (-(f.tell() + 30 + len(name))) % 4      # bytes wanted before the data
            extra = zm + 4                               # +4: a TLV record needs a header
            zi.extra = struct.pack("<HH", PAD_ID, extra - 4) + bytes(extra - 4)
        dst.writestr(zi, data)
    dst.close()
PY

echo "==> 5b/6 alignment report"
python3 - "$OUT/ztrackpad-unsigned.apk" <<'PY'
import sys, zipfile, struct
p = sys.argv[1]
z = zipfile.ZipFile(p)
bad = []
for i in z.infolist():
    # locate local header to compute the data offset
    with open(p, 'rb') as f:
        f.seek(i.header_offset)
        raw = f.read(30)
        if raw[:4] != b'PK\x03\x04':
            bad.append((i.filename, 'bad-local-header')); continue
        nlen, elen = struct.unpack('<HH', raw[26:30])
        data_off = i.header_offset + 30 + nlen + elen
    stored = i.compress_type == zipfile.ZIP_STORED
    ctype = 'STORED' if stored else 'DEFLATE'
    ok = (data_off % 4 == 0) if stored else True
    print(f"   {i.filename:24} {ctype:8} off={data_off:8} align4={'OK' if ok else 'BAD'}")
    if stored and not ok:
        bad.append((i.filename, f'offset {data_off} not 4-aligned'))
print()
if bad:
    print('   !! alignment problems:', bad)
    sys.exit(1)
print('   alignment OK (all STORED entries 4-byte aligned)')
PY

echo "==> 6/6 sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -storepass "$KSPASS" -keypass "$KSPASS" \
    -alias ztrackpad -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=So7o Z Trackpad, OU=dev, O=local, L=., S=., C=US" >/dev/null 2>&1
  echo "    generated $KS"
fi
apksigner sign \
  --ks "$KS" --ks-pass "pass:$KSPASS" --key-pass "pass:$KSPASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/ztrackpad.apk" "$OUT/ztrackpad-unsigned.apk"

apksigner verify --print-certs "$OUT/ztrackpad.apk" | head -6
echo
echo "APK: $OUT/ztrackpad.apk  ($(du -h "$OUT/ztrackpad.apk" | cut -f1))  v$VERSION_NAME ($VERSION_CODE)"
