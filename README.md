# Battery Status (Shizuku)

Shizuku または root 権限を使って、Android の充電速度やバッテリーの詳細ステータスをリアルタイムで表示するアプリ。

## 見れるもの

| 区分 | 項目 | Shizuku |
|---|---|---|
| 概要 | 残量 %、状態 (充電中/放電中/満充電)、電源 (AC/USB/ワイヤレス)、満充電までの時間 | 不要 |
| 電力 | 電流 (mA)、電圧 (V)、電力 (W)、平均電流、直近 120 サンプルの電力グラフ | 不要 |
| 充電器 | `dumpsys battery` の最大充電電流/電圧 (ネゴシエート値)、充電器側の電圧/電流/入力電力、充電器タイプ (SDP/DCP/PD…)、USB-C モード、充電フェーズ (Fast/Taper/Trickle) | **必要** |
| 健康状態 | 温度、ヘルス、サイクル数、設計容量、満充電容量 (学習値)、`batterystats` の推定容量、容量の健全度 % | ほぼ必要 |
| 詳細 | BatteryManager の生の値、`dumpsys battery` 全項目、`/sys/class/power_supply/*` の全ファイル、`batterystats` 抜粋 | 必要 |
| Raw | 上記の生テキスト (コピー可) | 必要 |
| 未知の sysfs | アプリが解釈していない power_supply 項目の件数と一覧 (コピーして報告できる) | 必要 |
| 診断 | 設定 → 診断から、端末情報・生データ・アプリ内ログをまとめたレポートを共有 / ファイル保存 / コピー | 不要 |
| ウィジェット | ネゴシエートした「電圧 × 電流 = W」、USB タイプ、実測の充電器電力、バッテリー電流。充電中は約 60 秒ごと、アプリ起動中はリアルタイム、タップで即更新 | 必要 (値のため) |
| 接続時通知 | 充電器をつないだら、ネゴシエート値とバッテリー電力を通知に表示。「接続後 30 秒だけ」と「充電中はずっと」を選べる | 必要 (値のため) |

Shizuku なしでも BatteryManager から取れる範囲 (電流・電圧・W 数など) は表示される。

## 仕組み

- 特権実行は `ShellBackend` として抽象化。**Shizuku** は UserService (`ShellService`) を shell 権限 (uid 2000) のプロセスとして起動して AIDL 経由で `sh -c` を実行、**Root** は `su -c` を直接実行する。設定で「自動 (Shizuku 優先) / Shizuku / Root」を選べる。
- 充電器の接続検知: `ACTION_POWER_CONNECTED` はマニフェスト受信できない (Android 8 以降の暗黙ブロードキャスト制限の例外外) ので、(1) プロセスが生きている間は実行時登録レシーバで即時、(2) アプリを開いている間は UI ループで即時、(3) それ以外は「充電中」制約の永続 JobScheduler ジョブで検知する。(3) はシステムが充電中と判定した時点 (残量が 1% 上がった頃) になるので、挿してから数十秒〜数分遅れる。
- 通知とウィジェット更新は JobScheduler のジョブ (`ChargingJobService`)。充電中はウィジェット更新ジョブが自分を 60 秒後に再スケジュールする (充電が切れると制約で止まる)。WorkManager は未完了ジョブの有無で自分のコンポーネントを有効/無効にして `PACKAGE_CHANGED` を発火させ、ウィジェットがプレースホルダに戻ってチカつくので使っていない。
- 画面更新 (BatteryManager) と shell 取得は別ループ。shell 側は毎回 `dumpsys battery` と各電源の `uevent` を 1 回の呼び出しで取り (`app/src/main/res/raw/collect.sh`)、ベンダー独自の sysfs ファイル全量は 15 秒ごと、重い `dumpsys batterystats` は 60 秒ごとに裏で取る。
- 電流の単位 (µA / mA) と符号 (自動 / そのまま / 反転) は設定から切り替え可能。端末によって報告が違うため。

## 必要なもの

- Android 8.0 (API 26) 以上
- [Shizuku](https://shizuku.rikka.app/) 11 以上 (Sui でも可)、または root (Magisk / KernelSU など)

## ビルド

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # R8 で縮小。keystore.properties があれば署名、なければ未署名
```

GitHub Actions (`.github/workflows/build.yml`) が push ごとに debug APK と未署名 release APK を Artifacts にアップロードする。`v*` の tag を push すると `.github/workflows/release.yml` が署名済み APK を GitHub Releases に出す。署名と配布の手順は [RELEASING.md](RELEASING.md)。

## 使い方

1. Shizuku を起動 (ワイヤレスデバッグ or root)
2. このアプリを開き、Shizuku カードの「許可する」を押す
3. シェルサービスが接続されると充電器・健康状態・Raw が埋まる

## F-Droid / 配布

- `fastlane/metadata/android/` に en-US / ja-JP の説明文を置いてある
- ネットワーク権限なし、解析ツールなし。依存は AndroidX / Compose / Shizuku API (Apache-2.0) のみ
- パッケージ名: `io.github.koki_dayo39.batterystatus`
- F-Droid 用メタデータの下書き: `metadata/io.github.koki_dayo39.batterystatus.yml`
- APK に Google 専用の依存情報ブロックを入れない設定済み (`dependenciesInfo`)

## 構成

```
app/src/main/
├── aidl/.../IShellService.aidl          # UserService の AIDL
├── java/io/github/koki_dayo39/batterystatus/
│   ├── shizuku/ShizukuManager.kt        # binder/権限/サービス bind の状態管理
│   ├── shizuku/ShellService.kt          # shell 権限で動く UserService
│   ├── shell/                           # ShellBackend 抽象化、RootShell、PrivilegeManager
│   ├── background/                      # 接続レシーバ、通知、WorkManager ジョブ
│   ├── widget/                          # ホーム画面ウィジェット
│   ├── data/BatteryRepository.kt        # BatteryManager + shell 出力のパース
│   ├── data/Derived.kt                  # 容量/充電器情報などの導出
│   └── ui/                              # Compose 画面 (概要 / 詳細 / Raw / 設定)
└── res/raw/collect.sh                   # shell 側で実行する収集スクリプト
```

## ライセンス

Copyright (C) 2026 koki-dayo39

[GNU General Public License v3.0 or later](LICENSE)
