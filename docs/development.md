# 開発規約

## ブランチと変更単位

- `main`は常にビルド可能な状態を保つ。
- 1つの変更では1つの目的を扱う。
- DB schema、Markdown形式、同期規約の変更は対応する文書も同時に更新する。

## 必須チェック

```shell
./gradlew lintDebug testDebugUnitTest assembleDebug
```

## コーディング方針

- Kotlin公式スタイルを使用する。
- UIからDAOやDrive APIを直接呼ばない。
- 時刻取得、UUID生成、ネットワーク境界はテストで差し替え可能にする。
- メモ本文、タイトル、OAuthトークンをログへ出力しない。
- Room migrationで`fallbackToDestructiveMigration`を使用しない。

## Definition of Done

- 受け入れ条件を満たす。
- 単体テストまたは妥当な検証手順がある。
- lint、unit test、debug buildが成功する。
- データ形式や利用者挙動が変わる場合は文書を更新する。

