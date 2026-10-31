# Utility Hub

One Android app, one home screen (grid of menus), 5 apps merged:

| Menu | Source |
|---|---|
| All Media Studio | native Kotlin -> `native/media_studio` |
| All-in-One Tools | native Kotlin -> `native/alltools` |
| File Manager Pro | native Kotlin/Compose -> `native/filemanager` (search now recursive) |
| APK Studio | native Kotlin -> `native/apkstudio` (edit APK name/icon, install from ZIP without extracting) |
| Project Vault | Flutter -> `lib/` |

## Build the APK on GitHub
1. Create a repo, upload **everything** in this folder (keep `.github/workflows/build-apk.yml`).
2. Actions -> **Build Utility Hub APK** -> Run workflow.
3. Download artifact `Utility-Hub-apk`, unzip, install `app-release.apk`.

## Add another menu
Native: add a module under `native/`, list it in `MODULES` in `tools/patch_android.py`, add a case in the generated MainActivity template, and a tile in `lib/hub.dart`.
