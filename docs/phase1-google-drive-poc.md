# Phase 1 — Google Drive/OAuth PoC

## Android側の検証範囲

アプリの「接続テストを実行」から、`drive.file`スコープだけを要求して次を検証する。

1. `SoliMemo`フォルダを検索し、なければ作成する。
2. その配下の`notes`フォルダを検索し、なければ作成する。
3. UUIDをファイル名とするMarkdownファイルを作成する。
4. ファイルを読み戻し、内容が一致することを確認する。
5. ファイルを更新して再度読み戻し、内容が一致することを確認する。

アクセストークンはメモリ上でAPI呼び出しに使用するだけで、Room、設定ファイル、ログへ保存しない。

## Google Cloud設定

1. Google Cloud Consoleでプロジェクトを作成または選択する。
2. Google Drive APIを有効にする。
3. Google Auth PlatformのBranding、Audience、Data Accessを設定する。
4. Data Accessへ次のスコープを追加する。

   ```text
   https://www.googleapis.com/auth/drive.file
   ```

5. AudienceがExternalかつTestingの場合、使用するGoogleアカウントをTest usersへ追加する。
6. OAuth ClientをAndroidアプリとして作成する。

   | 項目 | 値 |
   | --- | --- |
   | Package name | `com.digihori.solimemo` |
   | SHA-1 | 使用するdebugまたはrelease署名証明書のSHA-1 |

debug SHA-1は次で確認できる。

```shell
./gradlew signingReport
```

この方式ではクライアントシークレットや`google-services.json`をAndroidアプリへ配置しない。

## 成功条件

- アプリ画面に「成功」が表示される。
- Google Driveに`SoliMemo/notes/<UUID>.md`が存在する。
- ファイル本文が「Androidから更新したPhase 1 PoCメモ」になっている。
- OAuth同意画面の権限が`drive.file`だけである。

## Phase 1の残作業

- Web OAuthクライアントを作成する。
- Androidが作成したファイルをWebクライアントから列挙・取得できるか検証する。
- Webで更新したファイルをAndroidから再取得できるか検証する。
- 検証結果をADRへ記録し、GO/NO-GOを判定する。

## Web PoCの実行

Google Auth PlatformでWeb OAuthクライアントを作り、Authorized JavaScript originsへ`http://localhost:8000`を追加する。

リポジトリのルートで次を実行する。

```shell
python3 -m http.server 8000 --directory web
```

ブラウザで`http://localhost:8000`を開き、次を行う。

1. Web Client IDを入力する。
2. 「Google Driveへのアクセスを許可」を押し、Androidと同じアカウントを選ぶ。
3. 「Markdownを検索」を押す。
4. Androidが作成したUUID名のファイルを選ぶ。
5. 「選択したファイルを読み込む」を押す。
6. 本文を`Webから更新したPhase 1 PoCメモ`などへ変更する。
7. 「編集内容をDriveへ保存」を押す。

`fileId`をWeb PoCへ手入力せずに検索できることが、クライアント間可視性の確認条件となる。

## Web更新をAndroidで再取得

Web PoCで保存した後、Androidアプリで「Driveから最新メモを再取得」を押す。最新のMarkdownをファイルID指定なしで検索し、ファイル名、Drive version、更新日時、本文を表示する。本文にWebで加えた変更が表示されれば、WebからAndroidへの更新取得も成功となる。
