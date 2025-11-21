package me.itstautvydas.uuidswapper;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
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
    private final ProxyServer server;

    // サーバー移動情報を一時保存するためのマップ（再接続用）
    private final ConcurrentMap<String, TargetInfo> pendingSwaps = new ConcurrentHashMap<>();

    // 現在接続中のプレイヤーの「元の情報」を保持するマップ
    // Key: 現在(入れ替え後)のUUID, Value: 元のプロフィール情報
    private final ConcurrentMap<UUID, SessionData> sessions = new ConcurrentHashMap<>();

    // 再接続待ちの情報
    public static class TargetInfo {
        final String targetServerName;
        final String customUUID;
        final String customUsername;
        final UUID originalUUID;

        public TargetInfo(String targetServerName, String customUUID, String customUsername, UUID originalUUID) {
            this.targetServerName = targetServerName;
            this.customUUID = customUUID;
            this.customUsername = customUsername;
            this.originalUUID = originalUUID;
        }
    }

    // プレイヤーのオリジナルの識別情報を保持するクラス
    public static class SessionData {
        final String originalUsername;
        final UUID originalUUID;

        public SessionData(String originalUsername, UUID originalUUID) {
            this.originalUsername = originalUsername;
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

        config.swappedUuids = (Map) toml.getTable("swapped-uuids").toMap();
        config.customPlayerNames = (Map) toml.getTable("custom-player-names").toMap();

        logger.info("Configuration loaded.");
        // ... logging omitted ...
    }

    public GameProfile createProfile(String username, String uuid, GameProfile profile) {
        if (username == null)
            username = profile.getName();
        return new GameProfile(uuid == null ? profile.getId() : UUID.fromString(uuid), username, profile.getProperties());
    }

    // コンフィグ検索用メソッド
    public String getSwappedValueByKey(Map<String, Map<String, Object>> map, String originalUsername, UUID originalUUID, String serverName) {
        Object entryObject = map.get("u:" + originalUsername);
        if (entryObject == null) {
            entryObject = map.get("\"u:" + originalUsername + "\"");
        }
        if (entryObject == null) {
            entryObject = map.get(originalUUID.toString());
        }
        if (entryObject == null) {
            entryObject = map.get("\"" + originalUUID.toString() + "\"");
        }

        if (entryObject == null) {
            return null;
        }

        if (entryObject instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> entryMap = (Map<String, Object>) entryObject;
            var serverSpecificValue = entryMap.get(serverName);
            if (serverSpecificValue != null) return serverSpecificValue.toString();

            var defaultValue = entryMap.get("default");
            if (defaultValue != null) return defaultValue.toString();
        } else if (entryObject instanceof String) {
            return entryObject.toString();
        }

        return null;
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        var profile = event.getGameProfile();

        // ここで取得できる情報は、クライアントから送られてきた「本当のオリジナル情報」です。
        String originalUsername = profile.getName();
        UUID originalUUID = profile.getId();

        TargetInfo info = pendingSwaps.get(originalUsername);

        String newUsername = null;
        String newUUIDStr = null;
        boolean isSwapping = false;

        if (info != null) {
            // 再接続時
            newUsername = info.customUsername;
            newUUIDStr = info.customUUID;
            isSwapping = true;
            logger.info("UUID swap activated for re-connect to server {}.", info.targetServerName);
            pendingSwaps.remove(originalUsername);
        } else {
            // 初回接続時
            final String serverName = "default";
            newUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, serverName);
            newUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, serverName);
            logger.info("UUID swap applied for initial connection (default).");
        }

        if (newUsername != null || newUUIDStr != null) {
            var newProfile = createProfile(newUsername, newUUIDStr, profile);
            event.setGameProfile(newProfile);

            // ★ 重要: 入れ替え後のUUIDをキーにして、元の情報をセッションマップに保存
            // これにより、後で「usagimaru21」から「androidpotato」を逆引きできるようにする
            UUID resultingUUID = newProfile.getId();
            sessions.put(resultingUUID, new SessionData(originalUsername, originalUUID));

            if (isSwapping && newUUIDStr != null) {
                UUID newPlayerId = UUID.fromString(newUUIDStr);
                TargetInfo updatedInfo = new TargetInfo(info.targetServerName, newUUIDStr, newUsername, info.originalUUID);
                pendingSwaps.put(newPlayerId.toString(), updatedInfo);
            }
        } else {
            // 変更がない場合でもセッション情報は保存しておく（検索のため）
            sessions.put(profile.getId(), new SessionData(originalUsername, originalUUID));
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) return;

        var player = event.getPlayer();
        String currentPlayerKey = player.getUniqueId().toString();

        // 再接続時の転送処理
        if (pendingSwaps.containsKey(currentPlayerKey)) {
            TargetInfo info = pendingSwaps.get(currentPlayerKey);
            server.getServer(info.targetServerName).ifPresentOrElse(target -> {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(target));
            }, () -> logger.error("Failed to find target server {}.", info.targetServerName));
            pendingSwaps.remove(currentPlayerKey);
            return;
        }

        // 通常のサーバー移動処理
        RegisteredServer targetServer = event.getOriginalServer();
        String targetServerName = targetServer.getServerInfo().getName();

        // ★ 修正: Playerから直接名前を取るのではなく、sessionsから「元の名前」を取得して検索に使用する
        SessionData session = sessions.get(player.getUniqueId());

        // セッション情報がない場合は、現在の情報をそのまま使う（フォールバック）
        String originalUsername = (session != null) ? session.originalUsername : player.getUsername();
        UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

        String requiredUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, targetServerName);
        String requiredUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, targetServerName);

        String defaultUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, "default");
        String defaultUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, "default");

        if (requiredUUIDStr == null) requiredUUIDStr = defaultUUIDStr;
        if (requiredUsername == null) requiredUsername = defaultUsername;

        String currentUUIDStr = player.getUniqueId().toString();
        String currentUsername = player.getUsername(); // 現在の表示名

        boolean uuidChanged = requiredUUIDStr != null && !requiredUUIDStr.equals(currentUUIDStr);
        boolean usernameChanged = requiredUsername != null && !requiredUsername.equals(currentUsername);

        if (uuidChanged || usernameChanged) {
            TargetInfo info = new TargetInfo(targetServerName, requiredUUIDStr, requiredUsername, originalUUID);
            // 再接続時は元の名前で検索するため、originalUsernameをキーにする
            pendingSwaps.put(originalUsername, info);

            logger.info("Player {} (Original: {}) requested server {}. Disconnecting for update.",
                    currentUsername, originalUsername, targetServerName);

            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.disconnect(Component.text("§c[UUID Swapper] UUIDを更新するため再接続が必要です。"));
        }
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        var player = event.getPlayer();
        String currentIdStr = player.getUniqueId().toString();
        String currentServerName = event.getServer().getServerInfo().getName();

        if (pendingSwaps.containsKey(currentIdStr)) {
            TargetInfo info = pendingSwaps.remove(currentIdStr);
            server.getServer(info.targetServerName).ifPresent(target ->
                    player.createConnectionRequest(target).connect());
            return;
        }

        // デフォルトに戻す判定
        SessionData session = sessions.get(player.getUniqueId());
        String originalUsername = (session != null) ? session.originalUsername : player.getUsername();
        UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

        String defaultUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, "default");
        String targetUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, currentServerName);

        if (defaultUUIDStr == null) return;
        if (targetUUIDStr == null) targetUUIDStr = defaultUUIDStr;

        if (!currentIdStr.equals(defaultUUIDStr)) {
            if (!currentIdStr.equals(targetUUIDStr)) {
                logger.info("Triggering disconnect to revert UUID for {}.", originalUsername);
                player.disconnect(Component.text("§c[UUID Swapper] UUIDをリセットするため再接続が必要です。"));
            }
        }
    }

    // 退出時にセッション情報を削除
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }
}