# UUIDSwapper Fork
Original author:ItsTauTvyDas  
https://github.com/ItsTauTvyDas  
Original Project  
https://github.com/ItsTauTvyDas/UUIDSwapper  

forkを作成した自分は日本人です
英語が主流の言語じゃない人にとって英語から更に機械翻訳するよりは精度が高くなると思うので一応README-ja.mdに機械翻訳前の原文を置いておきます
また、元のプロジェクトとは別の方向性(自動取得による自動管理ではなく手動での複数サーバーにおける完全なUUID/ユーザー名管理)を重視してコーディング初心者がAIを用いながら作成したforkプロジェクトです
一部のメッセージは現在、作者の母国語でハードコードされていることに注意してください。
configでの多言語サポートのプルリクエストを歓迎します。
以下本文です

# プラグインの説明
configで指定したユーザーのUUIDとユーザー名を全てのサーバーにログインした時に偽装します
configで指定した特定のサーバーにログインした時にだけ別のUUIDとユーザー名で偽装します
以下のコマンドを利用することでconfigを無視して一時的に偽装先をオーバーライド出来ます

# コマンド
また、以下のコマンドが実装されています
/changeuuid 16進数のハイフン付きUUID
/changeusername 変更したいユーザー名
/changenow 今すぐ変更を適応
/swapuuid:creload 再起動せずconfigを即座に再読み込み

/changeuuidと/changeusernameを使ったあとにサーバーを移動すると移動先のサーバーで適応され/changenowを使うと今いるサーバーで適応できます
また、/changenowは何らかの理由でロビーにいかず素早くサーバーに入り直したい場合にも有用かもしれません


## Default configuration:
Configuration is TOML based and resides in `/plugins/uuid-swapper/config.toml`
```toml
# UUID Swapper by ItsTauTvyDas

# Only works when online mode is set to false
always-use-online-uuids = false

# Set to true if you still want to swap UUIDs even when always-use-online-uuids is enabled
swap-uuids = true

# UUID swapping
# If you want to use usernames, add u: prefix
[swapped-uuids]
#"u:androidpotato" = { default = "6a7b6452-ccd1-46a8-87b0-627ff930c818" }
#"u:androidpotato" = { default = "6a7b6452-ccd1-46a8-87b0-627ff930c818", mod1 = "03b90e2e-7397-35a0-ad43-e4ab6a9d1a97", pa1 = "87b4317f-c223-3ad9-96a2-3ee4a0415f28" }
#"u:androidpotato" = { default = "", mod1 = "03b90e2e-7397-35a0-ad43-e4ab6a9d1a97", pa1 = "87b4317f-c223-3ad9-96a2-3ee4a0415f28" }


# Set custom player names
# If you use UUID that has been previously swapped, use here the original
# For usernames, add u: prefix
[custom-player-names]
#"u:androidpotato" = { default = "", pa1 = "abcdefg", mod1 = "OfflinePotato" }
#"u:.androidP2653" = {default = "bedrockpotato1"}
#"00000000-0000-0000-0009-01f55a02cedb" = {default = "bedrockpotato1"}
```

注意点として、元のプロジェクトとは書き方が少し異なっている(複数サーバー対応の為ネストされている)のでそのままconfigファイルをコピーしてきた場合はエラーになると思います