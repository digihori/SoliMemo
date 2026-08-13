# 同期設計

## 原則

- AndroidではRoomを通常利用時の正本とする。
- Google Driveはバックアップとクライアント間同期を担う。
- ユーザー操作は先にRoomへ確定し、Drive通信を待たずUIへ反映する。
- 同期処理は冪等にし、同じ処理を再実行してもメモが重複しないようにする。
- 競合時は本文を失う可能性がある自動上書きを避ける。

## 状態

| 状態 | 意味 | 次の主な遷移 |
| --- | --- | --- |
| `LOCAL_ONLY` | ローカル作成済み、Driveファイル未作成 | `SYNCED` / `SYNC_ERROR` |
| `PENDING_UPLOAD` | 作成または更新をアップロード待ち | `SYNCED` / `CONFLICT` / `SYNC_ERROR` |
| `SYNCED` | Roomと既知のDrive版が一致 | `PENDING_UPLOAD` / `PENDING_DELETE` |
| `PENDING_DELETE` | 論理削除をDriveへ反映待ち | `SYNCED` / `CONFLICT` / `SYNC_ERROR` |
| `CONFLICT` | ローカルとDriveが同じ基点から別々に更新 | 競合コピー作成後に`SYNCED` |
| `SYNC_ERROR` | 認証・通信・形式などで同期失敗 | 原因解消後に直前の保留状態へ戻す |

## アップロード

```text
Roomの保留レコードを取得
  ↓
既存driveVersionとDriveの現行版を比較
  ├─ 一致: Markdownをアップロード
  ├─ Driveファイルなし: 新規作成
  └─ 不一致: CONFLICT
  ↓
返却されたdriveFileId / driveVersionを保存
  ↓
SYNCED
```

## ダウンロード

```text
前回同期以降に変更されたDriveファイルを列挙
  ↓
Markdownを検証・解析
  ├─ ローカル変更なし: Roomへ反映してSYNCED
  ├─ 同一内容: メタデータのみ更新
  ├─ ローカルも変更: CONFLICT
  └─ 不正形式: 対象だけSYNC_ERROR
```

## 競合方針

Version 1では自動本文マージをしない。競合した両方の内容を別メモとして保存し、ユーザーが後から整理できるようにする。更新日時だけを競合判定の唯一の根拠にしない。

## Phase 1で決める事項

- Drive変更列挙に使用するAPIとページトークンの永続化方法
- `driveVersion`に採用するDriveメタデータ
- Android/Web別OAuthクライアントでのファイル可視性
- 認証取消後のローカルデータの扱い
- Drive上で手動削除されたファイルの扱い
