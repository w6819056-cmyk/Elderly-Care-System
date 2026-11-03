package com.smartelder.common;

/**
 * 平台角色常量，与需求文档 2.3 用户角色与权限对应。
 */
public final class Roles {

    public static final String ELDER = "ELDER";
    public static final String FAMILY = "FAMILY";
    public static final String PROVIDER = "PROVIDER";
    public static final String ORG_ADMIN = "ORG_ADMIN";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String SYS_ADMIN = "SYS_ADMIN";

    private Roles() {
    }

    public static boolean isAdmin(String role) {
        return PLATFORM_ADMIN.equals(role) || SYS_ADMIN.equals(role);
    }

    public static boolean isProviderSide(String role) {
        return PROVIDER.equals(role) || ORG_ADMIN.equals(role);
    }
}
