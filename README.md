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
| 接続時通知 | 充電器をつないだ瞬間から 30 秒間、ネゴシエート値とバッテリー電力を通知に表示 (設定で ON/OFF) | 必要 (値のため) |

Shizuku なしでも BatteryManager から取れる範囲 (電流・電圧・W 数など) は表示される。

## 仕組み

- 特権実行は `ShellBackend` として抽象化。**Shizuku** は UserService (`ShellService`) を shell 権限 (uid 2000) のプロセスとして起動して AIDL 経由で `sh -c` を実行、**Root** は `su -c` を直接実行する。設定で「自動 (Shizuku 優先) / Shizuku / Root」を選べる。
- 接続時通知とウィジェット更新は JobScheduler の expedited ジョブ (`ChargingJobService`)。`ACTION_POWER_CONNECTED` で起動し、充電中はジョブが自分を 60 秒後に再スケジュールする (充電が切れると制約で止まる)。WorkManager は未完了ジョブの有無で自分のコンポーネントを有効/無効にして `PACKAGE_CHANGED` を発火させ、ウィジェットがプレースホルダに戻ってチカつくので使っていない。
- 画面更新 (BatteryManager) と shell 取得は別ループ。shell 側は毎回 `dumpsys battery` と各電源の `uevent` を 1 回の呼び出しで取り (`app/src/main/res/raw/collect.sh`)、ベンダー独自の sysfs ファイル全量は 15 秒ごと、重い `dumpsys batterystats` は 60 秒ごとに裏で取る。
- 電流の単位 (µA / mA) と符号 (自動 / そのまま / 反転) は設定から切り替え可能。端末によって報告が違うため。

## 必要なもの

- Android 8.0 (API 26) 以上
- [Shizuku](https://shizuku.rikka.app/) 11 以上 (Sui でも可)、または root (Magisk / KernelSU など)

## ビルド

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # R8 で縮小。個人利用向けに debug 鍵で署名される
```

GitHub Actions (`.github/workflows/build.yml`) が push ごとに両方の APK を Artifacts にアップロードする。

## 使い方

1. Shizuku を起動 (ワイヤレスデバッグ or root)
2. このアプリを開き、Shizuku カードの「許可する」を押す
3. シェルサービスが接続されると充電器・健康状態・Raw が埋まる

## F-Droid / 配布

- `fastlane/metadata/android/` に en-US / ja-JP の説明文を置いてある
- ネットワーク権限なし、解析ツールなし。依存は AndroidX / Compose / Shizuku API (Apache-2.0) のみ
- 公開前に LICENSE ファイルを追加すること (F-Droid は FOSS ライセンス必須)

## 構成

```
app/src/main/
├── aidl/.../IShellService.aidl          # UserService の AIDL
├── java/com/kokidayo/batterystatus/
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
