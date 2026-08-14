# Phase 4 — Web版

## 機能

- Google Identity Servicesによる`drive.file`認証
- Google Drive上のSoliMemo Markdown一覧
- 本文のみのクイック投稿
- タイトル・本文のリアルタイム検索
- タイトル・本文の編集
- `deletedAt`を使う論理削除
- Drive `version`を使う上書き競合防止
- Android版と同一のMarkdown Version 1形式
- デスクトップ・スマートフォン対応のレスポンシブUI

Web版は運営サーバーへメモを送信しない。アクセストークンはメモリにだけ保持し、OAuth Web Client IDだけをブラウザの`localStorage`へ保存する。

本番URLは`https://solimemo.digihori.com/`とする。プライバシーポリシーは`/privacy/`、利用規約は`/terms/`で公開する。本番Web OAuth Client IDは`web/config.js`の`googleClientId`へ設定し、Client Secretは配置しない。

## ローカル起動

```shell
python3 -m http.server 8000 --directory web
```

ブラウザで`http://localhost:8000`を開く。Google Cloud ConsoleのWeb OAuthクライアントには、JavaScript生成元として`http://localhost:8000`を登録する。

## 確認手順

1. 右上の3点ボタンからWeb Client IDを入力する。
2. 「Google Driveに接続」を押し、Android版と同じGoogleアカウントを選ぶ。
3. Androidで作成済みのメモがタイムラインへ表示されることを確認する。
4. 本文を入力して投稿し、Android版の同期後に表示されることを確認する。
5. Web版でタイトルまたは本文を編集し、Android版へ同期されることを確認する。
6. 検索欄でタイトルと本文を絞り込めることを確認する。
7. Web版で削除し、Android版の同期後に一覧から消えることを確認する。

## 配信時の注意

- 静的HTTPSホスティングを使用する。
- 本番URLをOAuthクライアントの「承認済みのJavaScript生成元」に追加する。
- Web Client IDをソースへ固定する場合も、Client Secretは絶対に配置しない。
- CSPなどのセキュリティヘッダーは配信先で設定する。
