package me.itstautvydas.uuidswapper;

import java.util.Map;

public class Configuration {
    Boolean alwaysUseOnlineUuids;
    Boolean swapUuids;

    // Key: 元のUUID/ユーザー名, Value: サーバー名と値のペアのマップ
    Map<String, Map<String, Object>> swappedUuids;
    Map<String, Map<String, Object>> customPlayerNames;
}