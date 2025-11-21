package me.itstautvydas.uuidswapper;

import com.google.inject.Inject;
import com.moandjiezana.toml.Toml;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.util.GameProfile;
import me.itstautvydas.BuildConstants;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
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

    private Configuration config; // Reloadに対応するため final を削除
    private final Logger logger;
    private final ProxyServer server;
    private final Path dataDirectory;

    // サーバー移動情報を一時保存するためのマップ（再接続用）
    private final ConcurrentMap<String, TargetInfo> pendingSwaps = new ConcurrentHashMap<>();

    // 現在接続中のプレイヤーの「元の情報」を保持するマップ
    // Key: 現在(入れ替え後)のUUID, Value: 元のプロフィール情報
    private final ConcurrentMap<UUID, SessionData> sessions = new ConcurrentHashMap<>();

    // コマンドによる一時的なオーバーライド情報を保持するマップ
    // Key: 元のUUID, Value: 一時的なオーバーライド情報
    private final ConcurrentMap<UUID, TemporaryOverride> overrides = new ConcurrentHashMap<>();

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

    // 一時的なオーバーライド情報
    public static class TemporaryOverride {
        String customUUID;
        String customUsername;

        public TemporaryOverride(String customUUID, String customUsername) {
            this.customUUID = customUUID;
            this.customUsername = customUsername;
        }
    }

    @Inject
    public UUIDSwapper(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, CommandManager commandManager) throws IOException {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;

        if (Files.notExists(dataDirectory))
            Files.createDirectories(dataDirectory);

        // 初期設定読み込み
        loadConfig();

        // コマンドの登録
        commandManager.register(commandManager.metaBuilder("changeUUID").plugin(this).build(), new ChangeUUIDCommand());
        commandManager.register(commandManager.metaBuilder("changePlayername").plugin(this).build(), new ChangePlayernameCommand());
        commandManager.register(commandManager.metaBuilder("changeNow").plugin(this).build(), new ChangeNowCommand());
        commandManager.register(commandManager.metaBuilder("swapuuid:creload").plugin(this).build(), new ReloadCommand());
    }

    // 設定読み込み処理をメソッド化
    private void loadConfig() {
        try {
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
            this.config = toml.to(Configuration.class);

            this.config.swappedUuids = (Map) toml.getTable("swapped-uuids").toMap();
            this.config.customPlayerNames = (Map) toml.getTable("custom-player-names").toMap();

            logger.info("Configuration loaded.");
        } catch (Exception e) {
            logger.error("Failed to load configuration.", e);
        }
    }

    public GameProfile createProfile(String username, String uuid, GameProfile profile) {
        if (username == null)
            username = profile.getName();
        return new GameProfile(uuid == null ? profile.getId() : UUID.fromString(uuid), username, profile.getProperties());
    }

    // コンフィグ検索用メソッド
    // ★修正点: 取得した値が空文字("")の場合は null を返して「変更なし」とする
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
            if (serverSpecificValue != null) {
                String val = serverSpecificValue.toString();
                return val.isEmpty() ? null : val;
            }

            var defaultValue = entryMap.get("default");
            if (defaultValue != null) {
                String val = defaultValue.toString();
                return val.isEmpty() ? null : val;
            }
        } else if (entryObject instanceof String) {
            String val = entryObject.toString();
            return val.isEmpty() ? null : val;
        }

        return null;
    }

    @Subscribe
    public void onGameProfileRequest(GameProfileRequestEvent event) {
        var profile = event.getGameProfile();

        String originalUsername = profile.getName();
        UUID originalUUID = profile.getId();

        TargetInfo info = pendingSwaps.get(originalUsername);

        String newUsername = null;
        String newUUIDStr = null;
        boolean isSwapping = false;

        if (info != null) {
            // 再接続時（pendingSwapsの情報を使用）
            // info内の値がnullの場合は、変更なし（元の値を使う）を意味する
            newUsername = info.customUsername;
            newUUIDStr = info.customUUID;
            isSwapping = true;
            logger.info("UUID swap activated for re-connect to server {}.", info.targetServerName);
            pendingSwaps.remove(originalUsername);
        } else {
            // 初回接続時
            final String serverName = "default";

            // オーバーライドチェック（初回接続から適用したい場合）
            TemporaryOverride override = overrides.get(originalUUID);

            if (override != null && override.customUsername != null) {
                newUsername = override.customUsername;
            } else {
                newUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, serverName);
            }

            if (override != null && override.customUUID != null) {
                newUUIDStr = override.customUUID;
            } else {
                newUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, serverName);
            }

            if (newUsername != null || newUUIDStr != null) {
                logger.info("UUID swap applied for initial connection (default/override).");
            }
        }

        if (newUsername != null || newUUIDStr != null) {
            var newProfile = createProfile(newUsername, newUUIDStr, profile);
            event.setGameProfile(newProfile);

            UUID resultingUUID = newProfile.getId();
            sessions.put(resultingUUID, new SessionData(originalUsername, originalUUID));

            if (isSwapping && newUUIDStr != null) {
                UUID newPlayerId = UUID.fromString(newUUIDStr);
                TargetInfo updatedInfo = new TargetInfo(info.targetServerName, newUUIDStr, newUsername, info.originalUUID);
                pendingSwaps.put(newPlayerId.toString(), updatedInfo);
            }
        } else {
            // 変更なしの場合もセッション情報を保存
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

        SessionData session = sessions.get(player.getUniqueId());
        String originalUsername = (session != null) ? session.originalUsername : player.getUsername();
        UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

        String requiredUUIDStr = null;
        String requiredUsername = null;

        // 1. オーバーライドの確認
        TemporaryOverride override = overrides.get(originalUUID);
        if (override != null) {
            if (override.customUUID != null) requiredUUIDStr = override.customUUID;
            if (override.customUsername != null) requiredUsername = override.customUsername;
        }

        // 2. コンフィグからの取得（オーバーライドがない場合）
        // 空文字設定の場合は null が返ってくる
        if (requiredUUIDStr == null) {
            requiredUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, targetServerName);
            if (requiredUUIDStr == null) {
                requiredUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, "default");
            }
        }

        if (requiredUsername == null) {
            requiredUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, targetServerName);
            if (requiredUsername == null) {
                requiredUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, "default");
            }
        }

        String currentUUIDStr = player.getUniqueId().toString();
        String currentUsername = player.getUsername();

        // ★修正: 設定がない(null)場合は、期待値を「元の値(Original)」とする
        String expectedUUIDStr = (requiredUUIDStr != null) ? requiredUUIDStr : originalUUID.toString();
        String expectedUsername = (requiredUsername != null) ? requiredUsername : originalUsername;

        // 現在の値と期待値が異なる場合に切断
        boolean uuidChanged = !currentUUIDStr.equals(expectedUUIDStr);
        boolean usernameChanged = !currentUsername.equals(expectedUsername);

        if (uuidChanged || usernameChanged) {
            TargetInfo info = new TargetInfo(targetServerName, requiredUUIDStr, requiredUsername, originalUUID);
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

        // 転送フォールバック
        if (pendingSwaps.containsKey(currentIdStr)) {
            TargetInfo info = pendingSwaps.remove(currentIdStr);
            server.getServer(info.targetServerName).ifPresent(target ->
                    player.createConnectionRequest(target).connect());
            return;
        }

        SessionData session = sessions.get(player.getUniqueId());
        String originalUsername = (session != null) ? session.originalUsername : player.getUsername();
        UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

        // オーバーライドのクリーンアップ
        if (overrides.containsKey(originalUUID)) {
            overrides.remove(originalUUID);
            return;
        }

        // デフォルトに戻す判定（またはサーバー固有設定への変更）
        String defaultUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, "default");
        String targetUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, currentServerName);

        if (targetUUIDStr == null) targetUUIDStr = defaultUUIDStr;

        // ★修正: ターゲット設定がなければ元のUUIDを期待値とする
        String expectedUUIDStr = (targetUUIDStr != null) ? targetUUIDStr : originalUUID.toString();

        if (!currentIdStr.equals(expectedUUIDStr)) {
            logger.info("Triggering disconnect to revert/update UUID for {}.", originalUsername);
            player.disconnect(Component.text("§c[UUID Swapper] UUIDを更新するため再接続が必要です。"));
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        SessionData session = sessions.remove(uuid);
        if (session != null) {
            overrides.remove(session.originalUUID);
        }
    }

    // --- コマンドクラス ---

    // /changeUUID <uuid>
    private class ChangeUUIDCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
                return;
            }
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(Component.text("This command can only be executed by a player.", NamedTextColor.RED));
                return;
            }
            Player player = (Player) invocation.source();
            String[] args = invocation.arguments();

            if (args.length != 1) {
                player.sendMessage(Component.text("Usage: /changeUUID <uuid>", NamedTextColor.RED));
                return;
            }

            SessionData session = sessions.get(player.getUniqueId());
            UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

            overrides.compute(originalUUID, (k, v) -> {
                if (v == null) return new TemporaryOverride(args[0], null);
                v.customUUID = args[0];
                return v;
            });

            player.sendMessage(Component.text("UUID override set to " + args[0] + ". Switch servers to apply.", NamedTextColor.GREEN));
        }

        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("uuidswapper.command.change");
        }
    }

    // /changePlayername <name>
    private class ChangePlayernameCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
                return;
            }
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(Component.text("This command can only be executed by a player.", NamedTextColor.RED));
                return;
            }
            Player player = (Player) invocation.source();
            String[] args = invocation.arguments();

            if (args.length != 1) {
                player.sendMessage(Component.text("Usage: /changePlayername <name>", NamedTextColor.RED));
                return;
            }

            SessionData session = sessions.get(player.getUniqueId());
            UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

            overrides.compute(originalUUID, (k, v) -> {
                if (v == null) return new TemporaryOverride(null, args[0]);
                v.customUsername = args[0];
                return v;
            });

            player.sendMessage(Component.text("Username override set to " + args[0] + ". Switch servers to apply.", NamedTextColor.GREEN));
        }

        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("uuidswapper.command.change");
        }
    }

    // /changeNow (現在のサーバーで即時適用)
    private class ChangeNowCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
                return;
            }
            if (!(invocation.source() instanceof Player)) {
                invocation.source().sendMessage(Component.text("This command can only be executed by a player.", NamedTextColor.RED));
                return;
            }
            Player player = (Player) invocation.source();

            if (player.getCurrentServer().isEmpty()) {
                player.sendMessage(Component.text("You are not connected to any server.", NamedTextColor.RED));
                return;
            }

            // 現在のサーバーを取得
            String targetServerName = player.getCurrentServer().get().getServerInfo().getName();

            SessionData session = sessions.get(player.getUniqueId());
            String originalUsername = (session != null) ? session.originalUsername : player.getUsername();
            UUID originalUUID = (session != null) ? session.originalUUID : player.getUniqueId();

            String requiredUUIDStr = null;
            String requiredUsername = null;

            // 1. オーバーライドの確認
            TemporaryOverride override = overrides.get(originalUUID);
            if (override != null) {
                if (override.customUUID != null) requiredUUIDStr = override.customUUID;
                if (override.customUsername != null) requiredUsername = override.customUsername;
            }

            // 2. コンフィグからの取得
            if (requiredUUIDStr == null) {
                requiredUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, targetServerName);
                if (requiredUUIDStr == null) {
                    requiredUUIDStr = getSwappedValueByKey(config.swappedUuids, originalUsername, originalUUID, "default");
                }
            }

            if (requiredUsername == null) {
                requiredUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, targetServerName);
                if (requiredUsername == null) {
                    requiredUsername = getSwappedValueByKey(config.customPlayerNames, originalUsername, originalUUID, "default");
                }
            }

            // 現在のサーバーに再接続するために情報を保存して切断
            TargetInfo info = new TargetInfo(targetServerName, requiredUUIDStr, requiredUsername, originalUUID);
            pendingSwaps.put(originalUsername, info);

            logger.info("Player {} executing /changeNow for server {}. Disconnecting for update.", originalUsername, targetServerName);
            player.disconnect(Component.text("§c[UUID Swapper] 設定を即時適用するため再接続します..."));
        }

        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("uuidswapper.command.change");
        }
    }

    // /swapuuid:creload (設定リロード)
    private class ReloadCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            if (!hasPermission(invocation)) {
                invocation.source().sendMessage(Component.text("You do not have permission to execute this command.", NamedTextColor.RED));
                return;
            }

            loadConfig();
            invocation.source().sendMessage(Component.text("UUID Swapper configuration reloaded.", NamedTextColor.GREEN));
        }

        @Override
        public boolean hasPermission(final Invocation invocation) {
            return invocation.source().hasPermission("uuidswapper.command.admin");
        }
    }
}