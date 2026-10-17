package fr.nbank.reconciliation.security;

public final class ActorContext {

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    private ActorContext() {
    }

    public static Actor get() {
        Actor actor = CURRENT.get();
        if (actor == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "Acteur requis");
        }
        return actor;
    }

    static void set(Actor actor) {
        CURRENT.set(actor);
    }

    static void clear() {
        CURRENT.remove();
    }
}
