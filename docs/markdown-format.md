# Markdown保存形式

## Version 1

Google Driveでは、1メモを1つのUTF-8 Markdownファイルとして保存する。改行コードはLF、ファイル名は`<UUID>.md`とする。

```markdown
---
schemaVersion: 1
id: 35c15f2a-0000-4000-8000-000000000000
title: Android OAuthについて
createdAt: 2026-08-11T00:30:00.000Z
updatedAt: 2026-08-11T00:45:00.000Z
deletedAt: null
---

drive.file scopeを使えば、
Google Drive全体へのアクセスは不要。
```

## 規約

- front matterはYAMLの限定サブセットとして扱う。
- キー順は上記の順序で書き出すが、読み込み時は順序に依存しない。
- 日時はISO 8601のUTC表現を使用する。
- `title`がない場合は`title: null`とする。
- 本文はfront matter直後の空行からEOFまでとする。
- 本文末尾は改行1個に正規化する。
- 未知のfront matterキーは読み飛ばし、将来バージョンとの互換性を確保する。
- 未対応の`schemaVersion`は上書きせず、同期エラーとして隔離する。
- YAMLとして意味が変わる文字を含むタイトルは、JSON互換の二重引用符形式でエスケープする。

## 削除メモ

論理削除時もファイルを直ちに削除せず、`deletedAt`へUTC日時を設定する。完全削除の保持期間はPhase 3以降に決定する。

## パーサー受け入れ条件

- 日本語、絵文字、改行を往復して保持できる。
- front matterのキー順が違っても読める。
- CRLF入力を読み、LFで書き戻せる。
- 不正ファイルによって他のメモの同期が停止しない。

