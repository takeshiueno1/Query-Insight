package com.query.insight.auth;

public final class PasswordPolicy {
    public static final String REGEX = "^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,128}$";

    private PasswordPolicy() {
    }
}
