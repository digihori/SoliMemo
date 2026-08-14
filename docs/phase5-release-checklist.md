# Phase 5 — Google Play公開チェックリスト

## 1. リポジトリ内

- [x] targetSdk 36
- [x] `drive.file`のみを要求
- [x] プライバシーポリシー
- [x] 利用規約
- [x] ストア掲載文案
- [x] データセーフティ回答案
- [x] Android自動バックアップ無効化
- [x] ランチャーアイコン設定
- [ ] 512 × 512ストアアイコン
- [ ] 1024 × 500フィーチャーグラフィック
- [ ] スマートフォンのスクリーンショット
- [x] Application IDを`com.digihori.solimemo`へ確定
- [x] `versionCode`を1、`versionName`を1.0.0へ確定
- [ ] Play App Signingを前提に署名付きAABを生成

## 2. 公開Webサイト

- [ ] `https://solimemo.digihori.com/`をCloudflare Pagesで公開
- [x] 機能説明を公開用ファイルへ掲載
- [x] プライバシーポリシーへのリンクを公開用ファイルへ掲載
- [x] 利用規約へのリンクを公開用ファイルへ掲載
- [ ] Google Search Consoleでドメイン所有権を確認
- [ ] Web版を公開する場合はOAuthの承認済みJavaScript生成元へ追加

GitHub Pagesを使う場合、OAuth本番審査では所有ドメイン要件を満たすか事前確認する。可能なら自身が管理するカスタムドメインを使用する。

## 3. Google Cloud Console（本番用プロジェクト）

- [ ] 開発・検証環境とは別の本番用Google Cloudプロジェクトを作成
- [ ] Google Drive APIを有効化
- [ ] Google Auth PlatformのBrandingを設定
- [ ] アプリ名、サポートメール、ロゴを設定
- [ ] ホームページ、プライバシーポリシー、利用規約URLを設定
- [ ] Authorized domainsへ所有ドメインを設定
- [ ] Data Accessには`drive.file`だけを設定
- [ ] AudienceをExternal／Productionへ設定
- [ ] プロジェクト所有者・編集者の連絡先を最新化
- [ ] Android OAuthクライアントを本番package名とPlay App Signing SHA-1で作成
- [ ] Web OAuthクライアントへ本番Web URLを設定

`drive.file`は非機密スコープと案内されているため、機密・制限付きスコープ向けの追加セキュリティ評価は通常不要。ただし、同意画面・ブランド・ドメインに関する審査や確認はConsoleの表示に従う。

## 4. Play Console

- [ ] デベロッパーアカウントと連絡先を確認
- [ ] `com.digihori.solimemo`でアプリを作成
- [ ] Play App Signingを有効化
- [ ] ストア掲載情報と画像を登録
- [ ] プライバシーポリシーURLを登録
- [ ] アプリのアクセス権申告を入力
- [ ] 広告「なし」を申告
- [ ] データセーフティを入力
- [ ] コンテンツレーティング質問票を入力
- [ ] 対象年齢・ニュースアプリ等の各申告を完了
- [ ] AABを内部テストへアップロード
- [ ] Play Consoleが表示する事前審査レポートを確認
- [ ] 実際のPlay配布版でGoogle認証と双方向同期を確認
- [ ] 必要なテスター要件を満たして本番申請

## 5. リリース判定

- [x] `lintDebug testDebugUnitTest assembleDebug bundleRelease`成功（未署名AAB）
- [ ] 新規投稿・検索・編集・削除が実機で成功
- [ ] オフラインCRUDが成功
- [ ] Android→Drive→Web→Drive→Android同期が成功
- [ ] OAuth拒否・期限切れ・通信失敗後もローカルデータを失わない
- [ ] 削除済みメモがAndroidとWebの通常一覧へ再出現しない
- [ ] プライバシーポリシーと実装・データセーフティ回答が一致
