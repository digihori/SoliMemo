# SoliMemo

SoliMemo is a local-first, private timeline-style memo application for Android and the web.

Repository: <https://github.com/digihori/SoliMemo>

Web: <https://solimemo.digihori.com>

Publisher: デジホリ工房

## Requirements

- Android Studio with JDK 17
- Android SDK 36

## Build

```shell
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## Web

```shell
python3 -m http.server 8000 --directory web
```

Cloudflare PagesではRoot directoryを`web`、Build commandを空欄、Build output directoryを`.`に設定する。

## Documentation

- [Product specification](spec.md)
- [Technical design](docs/technical-design.md)
- [Data model](docs/data-model.md)
- [Markdown format](docs/markdown-format.md)
- [Synchronization design](docs/sync-design.md)
- [Phase 1 Google Drive/OAuth PoC](docs/phase1-google-drive-poc.md)
- [Phase 3 Google Drive sync](docs/phase3-google-drive-sync.md)
- [Phase 4 Web app](docs/phase4-web-app.md)
- [Phase 5 release checklist](docs/phase5-release-checklist.md)
- [Google Play store listing draft](docs/store-listing-ja.md)
- [Google Play Data safety draft](docs/play-data-safety-ja.md)
- [Development conventions](docs/development.md)
