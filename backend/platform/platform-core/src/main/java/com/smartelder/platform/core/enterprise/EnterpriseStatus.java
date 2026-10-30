package com.smartelder.platform.core.enterprise;

public enum EnterpriseStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean canTransitionTo(EnterpriseStatus target) {
        return switch (this) {
            case ACTIVE -> target == SUSPENDED || target == CLOSED;
            case SUSPENDED -> target == ACTIVE || target == CLOSED;
            case CLOSED -> false;
        };
    }
}
