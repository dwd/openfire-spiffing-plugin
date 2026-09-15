package org.igniterealtime.openfire.spiffing;

import java.util.List;

/** Persists catalogue entries. Implementations own their own atomicity and concurrency control. */
public interface CatalogStore {
    List<CatalogEntry> list();

    void add(CatalogEntry entry);

    void remove(String id);

    /** Clears the default flag on every stored entry; used before a new entry is added as the default. */
    void clearDefault();
}
