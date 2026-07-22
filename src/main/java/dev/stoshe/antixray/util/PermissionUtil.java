package dev.stoshe.antixray.util;

import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import java.util.UUID;

/** Permission-node definitions and checks for AntiXray. */
public final class PermissionUtil {
    public static final String PERM_ADMIN = "antixray.admin";
    /** Players with this node are exempt from obfuscation/detection (e.g. staff, spectators). */
    public static final String PERM_BYPASS = "antixray.bypass";

    private PermissionUtil() {
    }

    public static boolean has(UUID player, String node, boolean def) {
        if (player == null) {
            return false;
        }
        try {
            return PermissionsModule.get().hasPermission(player, node, def);
        } catch (Exception e) {
            return def;
        }
    }

    public static boolean isAdmin(UUID player) {
        return has(player, PERM_ADMIN, false);
    }

    public static boolean isBypassed(UUID player) {
        return has(player, PERM_BYPASS, false);
    }
}
