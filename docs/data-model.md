# データモデル

## Room schema version 1

`notes`を唯一の主要テーブルとする。

| 列 | Kotlin型 | NULL | 説明 |
| --- | --- | --- | --- |
| `id` | `String` | No | UUID v4。全クライアント共通の論理ID |
| `title` | `String?` | Yes | 任意タイトル。空文字は保存前に`null`へ正規化する |
| `body` | `String` | No | 本文。Version 1ではプレーンテキストとして編集する |
| `createdAtEpochMillis` | `Long` | No | 作成日時（UTC Unix epoch milliseconds） |
| `updatedAtEpochMillis` | `Long` | No | 内容を最後に変更した日時 |
| `deletedAtEpochMillis` | `Long?` | Yes | 論理削除日時。非NULLなら通常一覧から除外する |
| `syncState` | `SyncState` | No | 同期状態 |
| `driveFileId` | `String?` | Yes | DriveのファイルID。Drive未作成ならNULL |
| `driveVersion` | `String?` | Yes | 競合検出に使うDrive側バージョン識別子 |
| `lastSyncError` | `String?` | Yes | ユーザー秘密を含まない診断用エラー分類 |

## 不変条件

- `id`は作成後に変更しない。
- `createdAtEpochMillis <= updatedAtEpochMillis`とする。
- タイトルと本文の両方が空のメモは新規作成しない。
- 編集では`createdAtEpochMillis`を変更しない。
- 通常の削除は行の物理削除ではなく`deletedAtEpochMillis`を設定して表現する。
- ゴミ箱への移動では`updatedAtEpochMillis`を変更せず、本文を最後に更新した日時を維持する。
- ゴミ箱からの完全削除では、Drive削除の完了後に行を物理削除する。
- 完全削除待ちの`PENDING_PURGE`はゴミ箱一覧から即座に除外し、Drive削除が完了するまで端末DB内に同期待ちとして保持する。
- ゴミ箱から復元しても`updatedAtEpochMillis`は変更せず、削除前の更新日時を維持する。
- ユーザー操作による新規作成後は`LOCAL_ONLY`、同期済みメモの編集後は`PENDING_UPLOAD`、削除後は`PENDING_DELETE`、復元後は`PENDING_RESTORE`、完全削除待ちは`PENDING_PURGE`とする。
- Drive由来の更新を適用した直後は`SYNCED`とする。

## マイグレーション方針

- Roomのschema JSONを必ずGit管理する。
- 公開版では破壊的マイグレーションを使用しない。
- schema versionを上げる変更にはmigration testを追加する。
