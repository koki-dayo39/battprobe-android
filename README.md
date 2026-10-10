# Battery Status (Shizuku)

Shizuku 権限を使って、Android の充電速度やバッテリーの詳細ステータスをリアルタイムで表示するアプリ。

## 見れるもの

| 区分 | 項目 | Shizuku |
|---|---|---|
| 概要 | 残量 %、状態 (充電中/放電中/満充電)、電源 (AC/USB/ワイヤレス)、満充電までの時間 | 不要 |
| 電力 | 電流 (mA)、電圧 (V)、電力 (W)、平均電流、直近 120 サンプルの電力グラフ | 不要 |
| 充電器 | `dumpsys battery` の最大充電電流/電圧 (ネゴシエート値)、充電器側の電圧/電流/入力電力、充電器タイプ (SDP/DCP/PD…)、USB-C モード、充電フェーズ (Fast/Taper/Trickle) | **必要** |
| 健康状態 | 温度、ヘルス、サイクル数、設計容量、満充電容量 (学習値)、`batterystats` の推定容量、容量の健全度 % | ほぼ必要 |
| 詳細 | BatteryManager の生の値、`dumpsys battery` 全項目、`/sys/class/power_supply/*` の全ファイル、`batterystats` 抜粋 | 必要 |
| Raw | 上記の生テキスト (コピー可) | 必要 |

Shizuku なしでも BatteryManager から取れる範囲 (電流・電圧・W 数など) は表示される。

## 仕組み

- Shizuku の **UserService** (`ShellService`) を shell 権限 (uid 2000。Shizuku を root で動かしていれば root) のプロセスとして起動し、AIDL 経由で `sh -c` を実行する。
- 画面更新 (BatteryManager) と shell 取得は別ループ。shell 側は毎回 `dumpsys battery` と各電源の `uevent` を 1 回の呼び出しで取り (`app/src/main/res/raw/collect.sh`)、ベンダー独自の sysfs ファイル全量は 15 秒ごと、重い `dumpsys batterystats` は 60 秒ごとに裏で取る。
- 電流の単位 (µA / mA) と符号 (自動 / そのまま / 反転) は設定から切り替え可能。端末によって報告が違うため。

## 必要なもの

- Android 8.0 (API 26) 以上
- [Shizuku](https://shizuku.rikka.app/) 11 以上 (Sui でも可)

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

## 構成

```
app/src/main/
├── aidl/.../IShellService.aidl          # UserService の AIDL
├── java/com/kokidayo/batterystatus/
│   ├── shizuku/ShizukuManager.kt        # binder/権限/サービス bind の状態管理
│   ├── shizuku/ShellService.kt          # shell 権限で動く UserService
│   ├── data/BatteryRepository.kt        # BatteryManager + shell 出力のパース
│   ├── data/Derived.kt                  # 容量/充電器情報などの導出
│   └── ui/                              # Compose 画面 (概要 / 詳細 / Raw / 設定)
└── res/raw/collect.sh                   # shell 側で実行する収集スクリプト
```
