# Movie Hunter fork

This fork of [prajwalch/TorrentSearch](https://github.com/prajwalch/TorrentSearch) splits the
project so it can be embedded in [Movie Hunter](https://github.com/m3ugoat) as a library.
Work happens on the `movie-hunter` branch.

## What changed

- `:app` is now an Android library holding all of TorrentSearch's code and resources.
- `:standalone` is a thin app shell (application ID, signing, launcher entry, intent filters),
  so the original standalone app still builds: `./gradlew :standalone:assembleDebug`.
- `TorrentSearch.init(context)` and `TorrentSearch.searchIntent(context, query)` are the entry
  points for host apps.
- `MainActivity` finishes instead of backgrounding the task when it isn't the task root.
- The version lives in `gradle.properties` (`torrentsearch.versionCode` / `versionName`).

## Updating from upstream

```sh
git fetch upstream
git rebase upstream/main
```

Likely conflicts:

- `app/build.gradle.kts`: upstream version bumps. Move the new numbers into `gradle.properties`.
  Keep any new dependencies in `app/`. App-only settings go in `standalone/build.gradle.kts`.
- `app/src/main/AndroidManifest.xml`: new activities stay in the library manifest. New intent
  filters or `<application>` attributes go in `standalone/src/main/AndroidManifest.xml`.
- `TorrentSearchApplication.kt`: new Koin modules go in `torrentSearchModules()` in `TorrentSearch.kt`.
