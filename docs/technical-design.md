# SoliMemo 技術設計

## 目的

SoliMemoを、Androidではオフライン利用可能なローカルファーストアプリとして提供し、ユーザー自身のGoogle DriveをAndroid/Web間の同期媒体として利用する。

## 確定事項

| 項目 | 採用内容 |
| --- | --- |
| アプリ名 | SoliMemo |
| Android application ID | `tk.horiuchi.solimemo` |
| 最小Android API | 23 |
| UI | Jetpack Compose / Material 3 |
| AndroidローカルDB | Room |
| 非同期処理 | Kotlin Coroutines / Flow |
| クラウド保存 | Google Drive API（Phase 1で成立性を検証） |
| クラウド形式 | 1メモ1Markdownファイル |
| ID | UUID v4 |
| 日時 | 内部ではUTCのInstant相当、表示時に利用者のタイムゾーンへ変換 |

## アーキテクチャ方針

```text
Compose UI
    ↓
ViewModel / Use case
    ↓
Repository
    ├── Room（Androidの通常利用時の正本）
    └── Google Drive data source（同期先）
```

- UIはRoomから得られるFlowを表示し、ネットワーク完了を待たない。
- ドメイン層はAndroid UI、Room、Drive API固有の型へ依存させない。
- 同期処理はRepositoryの通常CRUDから分離し、再試行可能にする。
- Web版も同じMarkdown形式と同期規約に従う。
- Phase 0では単一`app`モジュールとし、ビルド負荷が正当化できるまでモジュールを増やさない。

## パッケージ方針

```text
tk.horiuchi.solimemo
├── data/local
├── data/remote
├── data/repository
├── domain/model
├── domain/usecase
└── ui
```

機能追加時は、責務に応じて上記パッケージへ配置する。循環依存を作らない。

## セキュリティとプライバシー

- メモ本文を開発者管理サーバーへ送信しない。
- OAuthトークンをログへ出力しない。
- OAuthクライアントシークレットをAndroidアプリへ埋め込まない。
- Driveアクセスは最小権限を採用し、`drive.file`で要件を満たせるかPhase 1で検証する。
- クラッシュログ導入時は本文、タイトル、検索語を収集しない。

## 未決定事項

- `drive.file`でAndroid/Webの別OAuthクライアント間共有が成立する条件
- Drive専用領域を通常フォルダと`appDataFolder`のどちらにするか
- Webアプリのフレームワークとホスティング先
- バックグラウンド同期の具体的なスケジューリング
- 全文検索を通常SQLからFTSへ切り替える時期

これらはPhase 1以降の検証結果をADRとして記録する。

