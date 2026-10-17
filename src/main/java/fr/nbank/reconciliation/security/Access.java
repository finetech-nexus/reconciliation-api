package fr.nbank.reconciliation.security;

public final class Access {

    private Access() {
    }

    public static Actor require(String... roles) {
        Actor actor = ActorContext.get();
        for (String role : roles) {
            if (actor.has(role)) {
                return actor;
            }
        }
        throw new ApiException(403, "ACCESS_DENIED", "Action refusée pour ce rôle");
    }

    public static Actor read() {
        return require(
                "RECON_ADMIN",
                "RECON_OPERATOR",
                "RECON_ANALYST",
                "RECON_APPROVER",
                "RECON_AUDITOR",
                "RECON_SERVICE");
    }
}
