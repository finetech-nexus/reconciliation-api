package fr.nbank.reconciliation.security;

import java.util.Set;
import java.util.UUID;

public record Actor(UUID id, Set<String> roles) {

    public boolean has(String role) {
        return roles.contains(role);
    }
}
