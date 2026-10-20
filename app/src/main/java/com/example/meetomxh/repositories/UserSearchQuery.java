package com.example.meetomxh.repositories;

/** Small, deterministic pieces of the Firestore user prefix-search contract. */
public final class UserSearchQuery {
    private UserSearchQuery() { }

    public static String normalize(String query) {
        return query == null ? "" : query.trim();
    }

    public static String prefixUpperBound(String normalizedQuery) {
        return normalizedQuery + "\uf8ff";
    }

    public static boolean isCurrent(String requestQuery, long requestVersion,
                                    String currentInput, long currentVersion) {
        return requestVersion == currentVersion
                && requestQuery.equals(normalize(currentInput));
    }
}
