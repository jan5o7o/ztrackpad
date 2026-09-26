# Third-party licences

Z Trackpad is MIT ([`LICENSE`](LICENSE)). It also vendors five jars in `libs/` so the build
needs no network, and `build.sh` feeds every one of them to `d8` — so their code ends up
inside `classes.dex` and their terms ship with the APK. Both licences below require their
text to be passed on, which is what this file and `licenses/` are for.

## Shizuku-API — MIT

| file | what it is |
|---|---|
| `libs/shizuku-api.jar` | the client API (`Shizuku`, listeners, `UserServiceArgs`) |
| `libs/shizuku-aidl.jar` | the binder interfaces it talks over |
| `libs/shizuku-shared.jar` | shared parcels/constants |
| `libs/shizuku-provider.jar` | the `ContentProvider` that hands the client its binder |

Unmodified, from [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) —
Copyright (c) 2021 RikkaW, MIT.

This is the **client** library only. Nothing of the Shizuku manager (the app that runs as
shell) is vendored or redistributed here, and Z Trackpad is not affiliated with or endorsed
by the Shizuku project. The app declares `moe.shizuku.manager.permission.API_V23`, which is
how a Shizuku client asks to be allowed to use the API — it is a request for permission from
the Shizuku app, not a permission the app claims on its own.

Full text: [`licenses/Shizuku-API-MIT.txt`](licenses/Shizuku-API-MIT.txt)

## androidx.annotation — Apache-2.0

`libs/androidx-annotation.jar` is AndroidX `annotation` (Google), licensed Apache-2.0.
It is used at compile time for the nullability annotations the Shizuku API and this app
carry; the classes are dexed in like the rest.

Full text: [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)
