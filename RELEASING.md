# リリース手順 (署名 / F-Droid / IzzyOnDroid)

## 署名ってなに

Android の APK には必ず「署名」がついていて、端末は **同じ鍵で署名された APK しかアップデートとして受け付けない**。

- 鍵 (keystore ファイル `.jks`) をなくすと、もう同じアプリとして更新を出せない。ユーザーはアンインストールして入れ直すしかなくなる
- 鍵が漏れると、他人が「正規のアップデート」を作れてしまう
- なので **鍵は git に入れない・バックアップを複数とる・パスワードはパスワードマネージャに** が鉄則

配布先ごとに使われる鍵が違う:

| 配布先 | 誰が署名する？ | ソース |
|---|---|---|
| GitHub Releases | 自分の鍵 | `release.yml` が tag push で作る APK |
| IzzyOnDroid | 自分の鍵 (GitHub Releases の APK をそのまま配る) | 同上 |
| F-Droid 本家 | **F-Droid の鍵** (F-Droid がソースからビルドして署名) | git tag |

F-Droid 版と GitHub/Izzy 版は鍵が違うので、ユーザーは途中で乗り換えられない (アンインストールが必要)。これは普通のこと。
(再現可能ビルドにすれば F-Droid も自分の署名で配れるけど、最初は気にしなくて OK)

## 1. 鍵を作る (最初の 1 回だけ)

JDK に入ってる `keytool` で作る。パスワードは強いものを。

```sh
keytool -genkeypair -v \
  -keystore battprobe-release.jks \
  -alias battprobe \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=koki-dayo39"
```

- `-validity 10000` は約 27 年。期限切れ = 更新不可なので長めに
- できた `.jks` はリポジトリの外に置く (`.gitignore` 済みだけど念のため)
- **今すぐ** `.jks` とパスワードをバックアップ (USB メモリ + クラウドの暗号化ストレージ など 2 か所以上)

証明書のフィンガープリント (IzzyOnDroid に聞かれることがある) は:

```sh
keytool -list -v -keystore battprobe-release.jks -alias battprobe | grep SHA256
```

## 2. ローカルで署名ビルドしたいとき

リポジトリ直下に `keystore.properties` を作る (`.gitignore` 済み):

```properties
storeFile=/path/to/battprobe-release.jks
storePassword=****
keyAlias=battprobe
keyPassword=****
```

`./gradlew assembleRelease` で `app/build/outputs/apk/release/app-release.apk` が署名済みで出る。
`keystore.properties` がなければ **未署名** の APK が出る (F-Droid のビルドサーバはこっちを使う)。

## 3. GitHub Actions に鍵を登録する

GitHub のリポジトリ → Settings → Secrets and variables → Actions → New repository secret で 4 つ登録:

| Secret 名 | 中身 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 battprobe-release.jks` の出力 (macOS は `base64 -i battprobe-release.jks`) |
| `SIGNING_STORE_PASSWORD` | keystore のパスワード |
| `SIGNING_KEY_ALIAS` | `battprobe` |
| `SIGNING_KEY_PASSWORD` | 鍵のパスワード (keytool で別にしてなければ store と同じ) |

## 4. リリースを出す

1. `app/build.gradle.kts` の `versionCode` を +1、`versionName` を上げる
2. `fastlane/metadata/android/{en-US,ja-JP}/changelogs/<versionCode>.txt` に変更点を書く
3. コミットして main に入れる
4. `git tag v1.0.0 && git push origin v1.0.0` (tag は `v` + versionName と一致させる。ズレてると CI が止める)
5. `release.yml` が署名済み APK を GitHub Releases に上げる

## 5. IzzyOnDroid に申請

1. 上の手順で GitHub Releases に APK がある状態にする
2. https://gitlab.com/IzzyOnDroid/repo/-/issues で「inclusion request」を立てる (テンプレがある)
   - リポジトリ URL、ライセンス (GPL-3.0-or-later)、Shizuku/root が必要なことを書く
3. 以降は新しい Release を出せば自動で拾われる

## 6. F-Droid 本家に申請

1. https://gitlab.com/fdroid/fdroiddata をフォーク
2. `metadata/io.github.koki_dayo39.battprobe.yml` (このリポジトリにある下書き) をコピー
3. `fdroid lint` / `fdroid build` が通るのを確認して Merge Request
   (ローカルに fdroidserver がなくても、MR の CI がチェックしてくれる)
4. 以降は `UpdateCheckMode: Tags` なので、新しい tag を push すれば自動で追従する

## 公開前チェックリスト

- [ ] 鍵を作ってバックアップした
- [ ] GitHub Secrets を登録した
- [ ] スクショを `fastlane/metadata/android/en-US/images/phoneScreenshots/1.png` … に置いた (ja-JP も任意で)
- [ ] アイコン `fastlane/metadata/android/en-US/images/icon.png` (512×512) を置いた
- [ ] アプリ名を確定した (`strings.xml` の `app_name` と `fastlane/.../title.txt`)
- [ ] `v1.0.0` を tag して Release ができた
