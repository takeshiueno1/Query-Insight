package com.query.insight.security;

import java.util.Set;

public record AccountPrincipal(long accountId, long employeeId, String accountPublicId, String employeePublicId,
        String displayName, Set<String> roles) {
}
