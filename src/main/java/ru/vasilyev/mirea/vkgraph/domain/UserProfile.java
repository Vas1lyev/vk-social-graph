package ru.vasilyev.mirea.vkgraph.domain;

public record UserProfile(long id, String screenName, String displayName, boolean seed, int level) {
    public UserProfile asSeed() {
        return new UserProfile(id, screenName, displayName, true, 0);
    }

    public UserProfile withLevel(int newLevel) {
        return new UserProfile(id, screenName, displayName, seed, Math.min(level, newLevel));
    }
}
