# DragonRaid テスト・実環境検証ガイド

DragonRaid は高速な単体テストに加え、**実 Folia 26.3 サーバー＋外部必須プラグイン（`packetevents`, `TheNextLvl Worlds`, `BetterModel`）** を起動する **無人ヘッドレス E2E 統合テスト** と **ローカル実クライアント接続用の開発サーバー（`runFolia`）** を備えています。

> [!NOTE]
> `foliaIntegrationTest` および `runFolia` タスクは実行時に Minecraft EULA（https://aka.ms/MinecraftEULA）への同意設定（`eula=true`）を適用します。
> また、検証用の `dragonraid-test-harness` プラグインは専用 Gradle Configuration（`testHarnessPlugin`）でのみ読み込まれ、本番配布用の `DragonRaid` Shadow JAR には一切含まれません。

---

## 1. 高速単体テスト（全モジュール JUnit 5）

```sh
./gradlew test --no-daemon --console=plain
```

---

## 2. ヘッドレス実 Folia 26.3 自動 E2E 統合テスト

実 Folia 26.3 サーバープロセスをクリーンな一時ディレクトリ（空きポート自動割当）で起動し、8 つの実機統合シナリオを実行して自動シャットダウン・ログ監査までを行います：

```sh
./gradlew :dragonraid-paper:foliaIntegrationTest --no-daemon --console=plain
```

### 自動検証される項目
1. **`01_plugin_and_services_bootstrap`**: Folia 26.3 上での `DragonRaid`, `packetevents`, `Worlds`, `BetterModel` 有効化、Bukkit `ServicesManager` 経由の全公開 SPI 解決、SQLite `dragonraid.db` 初期化。
2. **`02_content_pack_and_blueprint_catalog`**: 検証用コンテンツパック `dev_ender_dragon` の YAML 読込と `EncounterBlueprintRegistry` 連携。
3. **`03_folia_brigadier_commands`**: Folia `GlobalRegionScheduler` 上での実 Brigadier コマンド（`/dr`, `/dr admin packs`, `/dr admin progression status`, `/dr admin reward list`, `/dr admin reload`）実行と再読込整合性。
4. **`04_sqlite_unlock_economy_and_reward_pipeline`**: 実 SQLite への非同期アンロック永続化・キャッシュ破棄後再読込、冪等キー付き仮想通貨入金・出金、Loot Table 型報酬評価・適用および `DragonRaid-Script` 専用スレッドでの `SCRIPT_HOOK` 実行。
5. **`05_party_and_finder_eligibility_flow`**: パーティ結成・招待・承認・リーダー切断時の即時リーダー委譲・再接続、およびコンテンツファインダーの解放前後ゲート判定。
6. **`06_worlds_void_instance_arena_and_actor_models`**: 実 `TheNextLvl Worlds` による一時 Void `THE_END` ワールド（`dragonraid_instance_1`）生成、Folia `RegionScheduler` 上での円形アリーナ＆黒曜石柱ブロック実体検証、実エンティティに対する `ActorModelService`（`VANILLA` ＆ `BETTER_MODEL` フォールバック）検証、インスタンス破棄・ワールド削除。
7. **`07_vanilla_end_dragon_battle_progression`**: 実 `world_the_end` 上での `DragonBattle` 討伐済み同期・帰還ポータル生成・エンドゲートウェイ拡張。
8. **シャットダウン＆ログ監査**: `onDisable()` 完走確認、および `logs/latest.log` に `Thread failed main thread check`・`ERROR`・`SEVERE` が 0 件であることの監査。

レポート出力先：
- `dragonraid-paper/build/folia-integration-test/report/e2e-results.json`
- `dragonraid-paper/build/folia-integration-test/report/TEST-FoliaRealServerE2ETest.xml`

---

## 3. ローカル実クライアント接続テスト（`runFolia`）

Minecraft 26.3 クライアント（複数アカウントのオフライン接続含む）から実際にログインして GUI・HUD・演出・ボス戦を確認するには以下を実行します：

```sh
./gradlew :dragonraid-paper:runFolia --console=plain
```

- 初回起動時に `dragonraid-paper/run/folia/` 配下へ `online-mode=false` の開発用 `server.properties` と検証用パック `plugins/DragonRaid/contents/dev_ender_dragon/` が自動生成されます。
- テストハーネス無し（素の本番構成のみ）で起動したい場合は `-PwithoutTestHarness` を付与します：
  ```sh
  ./gradlew :dragonraid-paper:runFolia -PwithoutTestHarness --console=plain
  ```

### 実機確認フローと `/drtest` 補助コマンド

サーバーコンソールで `op <プレイヤー名>` を実行した上で、以下の手順で全機能を効率よく確認できます：

| コマンド | 用途 |
| :--- | :--- |
| `/drtest status` | ロード済みコンテンツパック・登録ブループリント・アクティブインスタンス状態を表示 |
| `/drtest kit` | 戦闘検証用の装備一式（剣・弓・盾・金リンゴ・パール等）を付与 |
| `/drtest portal-here` | 2 ブロック先に `END_PORTAL` を設置（接触で `END_PORTAL_TOUCH` 実績 → `dev_dragon_normal` 開放演出を確認） |
| `/drtest unlock-all` | 全コンテンツのアンロックキーを即座に付与 |
| `/cf` | コンテンツファインダー GUI を開く（`dev_practice_dragon` は初期状態で即突入可能） |
| `/p invite <player>` / `/p accept <leader>` | パーティ結成・右側 PacketEvents サイドバー HUD・`/pc` チャット・レディチェック演出を確認 |
| `/drtest boss-hp 1` | インスタンス内ドラゴンの HP を `1` に変更（1 撃で討伐完了 → クリア報酬・Hard 解放・バニラ進行同期・元座標帰還を確認） |
| `/drtest self-damage 100` | 自身に大ダメージを与えてダウン／蘇生／全滅ワイプ待機 → `/cf retry`・`/cf leave` を確認 |
