# Phase 3 — Google Drive同期

## 完成範囲

- ホーム画面の同期ボタンから`drive.file`権限を取得する。
- Roomの未同期メモを`SoliMemo/notes/<UUID>.md`へ送信する。
- Driveからアプリが参照可能なMarkdownを取得し、Roomへ反映する。
- Driveの`version`を既知バージョンとして保存し、同時変更を検出する。
- 同時変更時はローカル内容を「競合コピー」として残してからDrive版を反映する。
- 削除はDriveファイルを物理削除せず、Markdownの`deletedAt`として同期する。
- ファイル単位の失敗は他のメモの処理を止めず、`SYNC_ERROR`として再試行対象にする。

Phase 3の初回リリースでは、利用者が明示的に押す手動同期を採用する。認証・同期核を端末で検証した後、WorkManagerによる自動実行を追加する。

## 端末確認

1. オフラインまたは未認証の状態でメモを作成する。
2. ホーム右上の`↻`を押してGoogle Driveアクセスを許可する。
3. 同期完了の送信件数が1件以上であることを確認する。
4. Driveの`SoliMemo/notes`にUUID名のMarkdownがあることを確認する。
5. Web版で本文を変更して保存する。
6. Androidで再度`↻`を押し、取得件数が1件以上になることを確認する。
7. Androidのタイムラインと編集画面にWeb側の変更が反映されることを確認する。
8. AndroidとWebの両方で同じメモを同期前に変更し、Android同期後に「競合コピー」が残ることを確認する。

## 受け入れ条件

- Googleアカウント未接続でもローカルCRUDを利用できる。
- 同期を繰り返しても同じメモが重複しない。
- 日本語、絵文字、複数行本文、引用が必要なタイトルを往復できる。
- 競合時にAndroid側とDrive側のどちらの本文も失われない。
- `lintDebug`、`testDebugUnitTest`、`assembleDebug`が成功する。
