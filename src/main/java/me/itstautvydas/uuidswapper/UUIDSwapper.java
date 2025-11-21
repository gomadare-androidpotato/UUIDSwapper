package me.itstautvydas.uuidswapper;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.util.GameProfile;
import me.itstautvydas.BuildConstants;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Plugin(id = "uuid-swapper",
        name = "UUIDSwapper",
        version = BuildConstants.VERSION,
        description = "Allows swapping player UUIDs and Usernames based on target server.",
        url ="https://itstautvydas.me",
        authors ={"ItsTauTvyDas"})
public class UUIDSwapper {

    private final Configuration config;
    private final Logger logger;
    private final ProxyServer server; // 強制接続のためにProxyServerインスタンスを保持

    // サーバー移動情報を一時保存するためのマップ。
    // Key: プレイヤーのユーザー名 (String) (ServerPreConnect時) または新しいUUIDの文字列 (GameProfileRequest後)
    // Value: 目標サーバーとカスタムUUID/ユーザー名
    private final ConcurrentMap<String, TargetInfo> pendingSwaps = new ConcurrentHashMap<>();

    // サーバー移動時の情報を保持する内部クラス
    public static class TargetInfo {
        final String targetServerName;
        final String customUUID;
        final String customUsername;
        final UUID originalUUID; // 元のUUIDも保持

        // コンストラクタの引数順序: (目標サーバー名, カスタムUUID, カスタムユーザー名, 元のUUID)
        public TargetInfo(String targetServerName, String customUUID, String customUsername, UUID originalUUID) {
            this.targetServerName = targetServerName;
            this.customUUID = customUUID;
            this.customUsername = customUsername;
            this.originalUUID = originalUUID;
        }
    }

    @Inject
    public UUIDSwapper(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) throws IOException {
        this.server = server;
        this.logger = logger;

        if (Files.notExists(dataDirectory))
            Files.createDirectories(dataDirectory);

        Path configFile = dataDirectory.resolve("config.toml");
        if (Files.notExists(configFile)) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.toml")) {
                if (in != null) {
                    logger.info("Copying new configuration...");
                    Files.copy(in, configFile);
                }
            }
        }

        var toml = new Toml().read(configFile.toFile());
        config = toml.to(Configuration.class);

        // Mapの型変更に伴い、テーブルを読み込み、Mapにキャスト
        config.swappedUuids = (Map) toml.getTable("swapped-uuids").toMap();
        config.customPlayerNames = (Map) toml.getTable("custom-player-names").toMap();

        logger.info("Configuration loaded.");
        logger.info("Loaded {} swapped UUIDs.", config.swappedUuids.size());
        for (var entry : config.swappedUuids.entrySet()) {
            logger.info("# {} => {}", entry.getKey(), entry.getValue());
        }
        logger.info("Loaded {} custom player usernames.", config.customPlayerNames.size());
        for (var entry : config.customPlayerNames.entrySet()) {
            logger.info("# {} => {}", entry.getKey(), entry.getValue());
        }
    }

    /**
     * 新しいユーザー名とUUIDを含むGameProfileを作成します。
     */
    public GameProfile createProfile(String username, String uuid, GameProfile profile) {
        if (username == null)
            username = profile.getName();
        return new GameProfile(uuid == null ? profile.getId() : UUID.fromString(uuid), username, profile.getProperties());
    }

    /**
     * 設定マップから、指定されたサーバー名を考慮した入れ替え値を取得します。(GameProfile検索用: 主に初回/再接続時)
     * サーバー固有の設定がない場合は 'default' の値を使用します。
     */
    public String getSwappedValueForServer(Map<String, Map<String, Object>> map, GameProfile profile, String serverName) {
        // 1. 元のUUIDまたはユーザー名に対応する設定マップを取得
        // Tomlのキーの読み込みが複雑なため、UUID/ユーザー名両方のクォート有無をチェックします。
        Map<String, Object> entryMap = map.get("u:" + profile.getName());
        if (entryMap == null) {
            entryMap = map.get("\"u:" + profile.getName() + "\"");
        }
        if (entryMap == null) {
            entryMap = map.get(profile.getId().toString());
        }
        if (entryMap == null) {
            entryMap = map.get("\"" + profile.getId().toString() + "\"");
        }

        if (entryMap != null) {
            // 2. 接続先サーバー名に対応する入れ替え値を取得 (サーバー固有設定)
            var serverSpecificValue = entryMap.get(serverName);
            if (serverSpecificValue != null) {
                return serverSpecificValue.toString();
            }

            // 3. サーバー固有の設定がない場合、"default" の設定を取得 (全サーバー共通設定)
            var defaultValue = entryMap.get("default");
            if (defaultValue != null) {
                return defaultValue.toString();
            }
        }

        return null;
    }

    /**
     * 設定マップから、指定されたサーバー名を考慮した入れ替え値を取得します。(プレイヤー名検索用: 主にサーバー移動後)
     * サーバー固有の設定がない場合は 'default' の値を使用します。
     * @param map UUIDまたはユーザー名の設定マップ
     * @param playerName プレイヤーの現在のユーザー名（変更されていないもの）
     * @param serverName 目標サーバー名
     * @return サーバーに対応するUUIDまたはユーザー名
     */
    public String getSwappedValueForServerByName(Map<String, Map<String, Object>> map, String playerName, String serverName) {
        // 1. ユーザー名に対応する設定マップを取得
        Map<String, Object> entryMap = map.get("u:" + playerName);
        if (entryMap == null) {
            entryMap = map.get("\"u:" + playerName + "\"");
        }

        if (entryMap != null) {
            // 2. 接続先サーバー名に対応する入れ替え値を取得 (サーバー固有設定)
            var serverSpecificValue = entryMap.get(serverName);
            if (serverSpecificValue != null) {
                return serverSpecificValue.toString();
            }

            // 3. サーバー固有の設定がない場合、"default" の設定を取得 (全サーバー共通設定)
            var defaultValue = entryMap.get("default");
            if (defaultValue != null) {
                return defaultValue.toString();
            }
        }

        return null;
    }

    /**
     * 初回接続時の default 設定適用と、再接続時のカスタムUUID適用を行うイベントリスナー。
     * UUIDを変更できるのはこのタイミングのみです。
     */
    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        var profile = event.getGameProfile();
        String playerName = profile.getName();
        UUID originalPlayerId = profile.getId();

        // 1. pendingSwapsに情報があるか確認（再接続かどうか）
        // キーはユーザー名を使用
        TargetInfo info = pendingSwaps.get(playerName);

        String newUsername = null;
        String newUUIDStr = null;
        boolean isSwapping = false;

        // infoが存在すれば、サーバー移動のために切断され、再接続してきたと見なす
        if (info != null) {
            // ★ 再接続時（サーバー移動によるUUID変更要求）
            newUsername = info.customUsername;
            newUUIDStr = info.customUUID;
            isSwapping = true;
            logger.info("UUID swap activated for re-connect to server {}. Applying specific profile.", info.targetServerName);

            // 情報をpendingSwapsから削除（この後、新しいUUIDをキーとして再登録するため）
            pendingSwaps.remove(playerName);

        } else {
            // ★ 初回接続時（default設定のみ適用）
            final String serverName = "default";
            // GameProfileRequestEventでは、元のプロファイルを使用
            newUsername = getSwappedValueForServer(config.customPlayerNames, profile, serverName);
            newUUIDStr = getSwappedValueForServer(config.swappedUuids, profile, serverName);
            logger.info("UUID swap applied for initial connection (default).");
        }

        if (newUsername != null || newUUIDStr != null) {
            // 実際にプロファイルを変更
            var newProfile = createProfile(newUsername, newUUIDStr, profile);
            event.setGameProfile(newProfile);

            logger.info("Player's ({} {}) new profile is:", event.getUsername(), originalPlayerId);
            if (newUsername != null)
                logger.info(" # Username => {}", newUsername);
            if (newUUIDStr != null)
                logger.info(" # Unique ID => {}", newUUIDStr);

            // 2. UUIDが変更された場合、ServerPreConnectEventで転送先をオーバーライドするために、
            // 新しいUUIDをキーとして再登録する (isSwappingがtrueの場合のみ)
            if (isSwapping && newUUIDStr != null) {

                UUID newPlayerId = UUID.fromString(newUUIDStr);

                // TargetInfoを新しいUUIDと、元のUUID (info.originalUUID) を含めて再作成する
                TargetInfo updatedInfo = new TargetInfo(info.targetServerName, newUUIDStr, newUsername, info.originalUUID);

                // 新しいUUIDの文字列をキーとして情報を保存し直す
                pendingSwaps.put(newPlayerId.toString(), updatedInfo);
                logger.info("Updated pendingSwaps key from {} (name) to {} (new UUID string).", playerName, newPlayerId);
            }
        }
    }

    /**
     * ServerPreConnectEvent:
     * 1. サーバー移動要求を検出し、UUID変更が必要な場合は切断を指示します。
     * 2. 再接続時、デフォルトサーバーへの接続を検知し、目的のサーバーへ転送先をオーバーライドします。
     */
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        // 接続許可の確認
        if (!event.getResult().isAllowed()) return;

        var player = event.getPlayer();
        // 現在のプレイヤーのUUID（GameProfileRequestEventで変更された後のUUID）
        String currentPlayerKey = player.getUniqueId().toString();
        RegisteredServer targetServer = event.getOriginalServer();
        String targetServerName = targetServer.getServerInfo().getName();

        // **A. 再接続時の転送先オーバーライド**
        // GameProfileRequestEventでUUIDが変更された後の、デフォルトサーバーへの接続試行を検知
        if (pendingSwaps.containsKey(currentPlayerKey)) {
            // UUIDの変更が完了した後の、デフォルトサーバーへの接続試行である
            TargetInfo info = pendingSwaps.get(currentPlayerKey);

            logger.info("Interception: Player {} (new UUID {}) is being redirected from {} to {}.",
                    player.getUsername(), currentPlayerKey, targetServerName, info.targetServerName);

            // 転送先をオーバーライド
            server.getServer(info.targetServerName).ifPresentOrElse(target -> {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
            }, () -> {
                logger.error("Failed to find target server {} for post-swap redirection.", info.targetServerName);
            });

            // UUIDの変更と転送先オーバーライドが完了したので、情報を削除
            pendingSwaps.remove(currentPlayerKey);
            return;
        }

        // **B. 初回接続時/通常時の切断判定ロジック**

        // 接続先サーバー用のカスタムUUIDとユーザー名を取得
        // GameProfileRequestEvent で UUIDが変わる前なので、profileで検索
        String newUUIDStr = getSwappedValueForServer(config.swappedUuids, player.getGameProfile(), targetServerName);
        String newUsername = getSwappedValueForServer(config.customPlayerNames, player.getGameProfile(), targetServerName);

        // 比較用に default の値を取得
        String defaultUUIDStr = getSwappedValueForServer(config.swappedUuids, player.getGameProfile(), "default");
        String defaultUsername = getSwappedValueForServer(config.customPlayerNames, player.getGameProfile(), "default");

        // UUIDまたはユーザー名が default 設定と異なる場合に再接続ロジックを起動
        boolean uuidChanged = (newUUIDStr != null && !newUUIDStr.equals(defaultUUIDStr)) ||
                (newUUIDStr != null && defaultUUIDStr == null);
        boolean usernameChanged = (newUsername != null && !newUsername.equals(defaultUsername)) ||
                (newUsername != null && defaultUsername == null);

        if (uuidChanged || usernameChanged) {
            // ログ用に、実際に適用されるカスタム値を確定
            String finalCustomUUID = (newUUIDStr != null) ? newUUIDStr : defaultUUIDStr;
            String finalCustomUsername = (newUsername != null) ? newUsername : defaultUsername;

            // 1. 移動情報を一時保存 (キーはプレイヤー名を使用)
            // この情報は GameProfileRequestEvent で使用される
            TargetInfo info = new TargetInfo(targetServerName, finalCustomUUID, finalCustomUsername, player.getUniqueId());
            pendingSwaps.put(player.getUsername(), info);

            logger.info("Player {} requested server {}. Preparing for UUID/Username change via re-connect.", player.getUsername(), targetServerName);

            // 2. サーバー移動をキャンセルし、切断を指示（再接続を促す）
            event.setResult(ServerPreConnectEvent.ServerResult.denied());

            // プレイヤーが再接続すると、GameProfileRequestEvent が再度トリガーされます
            player.disconnect(Component.text("§c[UUID Swapper] UUIDを更新するため再接続が必要です。"));
        }
    }

    /**
     * ServerConnectedEvent:
     * 1. ServerPreConnectで転送に失敗した場合の保険ロジック。
     * 2. **カスタムUUIDを持っているプレイヤーが、そのUUIDを必要としないサーバーに接続した場合、
     * UUIDをデフォルトに戻すために切断を指示します。**
     */
    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        var player = event.getPlayer();
        String playerName = player.getUsername();
        String currentIdStr = player.getUniqueId().toString();
        String currentServerName = event.getServer().getServerInfo().getName();

        // **A. ServerPreConnectで転送に失敗した場合の保険ロジック (既存)**
        if (pendingSwaps.containsKey(currentIdStr)) {
            // このブロックは実行されるべきではありません。実行された場合、ServerPreConnectの転送に失敗した可能性があります。
            TargetInfo info = pendingSwaps.remove(currentIdStr);

            logger.warn("ServerConnectedEvent triggered for transfer fallback (should not happen). Manually connecting to {}.", info.targetServerName);

            // 目的のサーバーに強制的に転送する (保険)
            server.getServer(info.targetServerName).ifPresentOrElse(targetServer -> {
                event.getPlayer().createConnectionRequest(targetServer).connect();
            }, () -> {
                logger.error("Failed to find target server {} during ServerConnected fallback.", info.targetServerName);
            });
            return;
        }

        // **B. デフォルトUUIDに戻す必要があるかチェック**

        // 1. プレイヤーのカスタム設定全体から、default/target UUIDsを取得するために、
        //    playerName（カスタムUUID適用前でも不変）を使用して設定マップを検索する。
        String defaultUUIDStr = getSwappedValueForServerByName(config.swappedUuids, playerName, "default");
        String targetUUIDStr = getSwappedValueForServerByName(config.swappedUuids, playerName, currentServerName);

        // default設定がない場合は処理をスキップ
        if (defaultUUIDStr == null) {
            return;
        }

        // 2. 現在のUUIDがデフォルトUUIDではないことを確認 (カスタムUUIDが適用されている)
        if (!currentIdStr.equals(defaultUUIDStr)) {
            // プレイヤーはカスタムUUIDを持っている。

            // 3. 接続先サーバーが、現在のカスタムUUIDを要求しているかチェック

            // 接続先サーバーが要求するUUIDが、現在のUUIDと一致しない場合（カスタムUUIDが不要なサーバーに入った場合）
            // targetUUIDStrがnull（設定がない）場合や、currentIdStrとtargetUUIDStrが異なる場合にリセットが必要。
            boolean needsReset = targetUUIDStr == null || !currentIdStr.equals(targetUUIDStr);

            if (needsReset) {
                // ただし、現在のカスタムUUIDが、現在のサーバーのdefault設定と同じだった場合はリセット不要
                if (currentIdStr.equals(defaultUUIDStr)) {
                    return;
                }

                logger.info("Player {} connected to {} with a non-matching custom UUID ({}). Triggering disconnect to revert to default UUID ({}).",
                        playerName, currentServerName, currentIdStr, defaultUUIDStr);

                // pendingSwapsに情報を残さず切断。
                // 再接続時、GameProfileRequestEventのロジック（info == null）により default UUID が適用される。
                player.disconnect(Component.text("§c[UUID Swapper] UUIDをリセットするため再接続が必要です。"));
            }
        }
    }
}