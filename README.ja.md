# BattProbe

[English](README.md) | **日本語**

BattProbe は、バッテリーと充電器が実際に何をしているかを、基本的な値からカーネルの生データまで毎秒更新で表示する Android アプリです。

特別な権限なしでも動きます。[Shizuku](https://shizuku.rikka.app/) か root を許可すると、ネゴシエートされた充電電力、USB PD の詳細、容量の健全度、端末が公開している `/sys/class/power_supply` の全ノードまで読み取ります。

## 機能

| | 見れるもの | Shizuku / root |
|---|---|---|
| 概要 | 残量、充電状態、電源 (AC / USB / ワイヤレス)、満充電までの時間 | 不要 |
| 電力 | 電流、電圧、電力 (W)、平均電流、リアルタイムの電力グラフ | 不要 |
| 充電器 | ネゴシエートされた最大電流 / 電圧、充電器側の電圧・電流・入力電力、充電器タイプ (SDP / DCP / PD…)、USB-C モード、充電フェーズ (Fast / Taper / Trickle) | 必要 |
| 健康状態 | 温度、ヘルス、サイクル数、設計容量と満充電容量 (学習値)、容量の健全度 % | ほぼ必要 |
| Pixel | Battery Defender、自動調整充電、充電ステージ、残量帯ごとのサイクル分布などの Pixel 独自ノード | 必要 |
| 詳細・Raw | BatteryManager の全値、`dumpsys battery` 全項目、`power_supply` の全ファイル、`batterystats` 抜粋、コピーできる生テキスト | 必要 |
| 未知のノード | アプリがまだ解釈できない sysfs 項目の一覧 (コピーして報告できます) | 必要 |
| ウィジェット | ネゴシエートした「V × A = W」、USB タイプ、実測の充電器電力、バッテリー電流。タップで更新 | 値の取得に必要 |
| 接続時通知 | 充電器をつないだときにネゴシエート値を通知。「30 秒だけ」か「充電中ずっと」を選べます | 値の取得に必要 |
| 診断 | 端末情報・生データ・アプリ内ログをまとめたレポートを共有 / 保存 | 不要 |

報告される値はメーカーによって大きく違います。お使いの端末でおかしな値や足りない項目があれば、診断レポート (設定 → 診断) を添えて [Issue](https://github.com/koki-dayo39/battprobe-android/issues) を立ててください。

## プライバシー

- インターネット権限なし。データが端末の外に出ることはありません
- 解析ツール・広告・トラッキングなし
- 診断レポートは自分で作成したときだけ生成され、送り先も自分で選びます

## 必要なもの

- Android 8.0 以上
- 詳細データを見る場合: [Shizuku](https://shizuku.rikka.app/) 11 以上 (Sui も可) または root (Magisk、KernelSU など)

## ダウンロード

- [GitHub Releases](https://github.com/koki-dayo39/battprobe-android/releases)
- IzzyOnDroid / F-Droid: 準備中

F-Droid 版は F-Droid が署名し、GitHub Releases 版と IzzyOnDroid 版は開発者が署名しています。署名が違うものどうしでは上書き更新できないため、乗り換えるときは一度アンインストールしてください。

## 使い方

1. BattProbe をインストールして開くと、基本的な値がすぐに表示されます
2. 詳細データを見るには、Shizuku を起動する (ワイヤレスデバッグか root で) か、root を用意します
3. Shizuku カードの **許可する** (または **Root を使う**) をタップします
4. 接続されると、充電器・健康状態・詳細・Raw が埋まります

電流の符号や桁がおかしい端末では、設定から単位 (µA / mA) と符号を切り替えられます。

## ビルド

```sh
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # R8 で縮小。keystore.properties がなければ未署名
```

アプリの内部構成は [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) を見てください。

## ライセンス

Copyright (C) 2026 koki-dayo39

BattProbe はフリーソフトウェアです。[GNU General Public License v3.0 以降](LICENSE) のもとで公開しています。
