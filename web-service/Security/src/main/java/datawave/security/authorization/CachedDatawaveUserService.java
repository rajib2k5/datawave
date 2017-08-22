package datawave.security.authorization;

import java.util.Collection;

/**
 * A version of {@link DatawaveUserService} that provides cache control.
 */
public interface CachedDatawaveUserService extends DatawaveUserService {
    
    /**
     * Returns the {@link DatawaveUser} having the specified name as its {@link DatawaveUser#getName()} value.
     */
    DatawaveUser list(String name);
    
    /**
     * Lists the DNs for all entries in the cache.
     */
    Collection<? extends DatawaveUser> listAll();
    
    /**
     * Lists DNs for all entries in the cache where the {@link DatawaveUser#getName()} contains the supplied string.
     */
    Collection<? extends DatawaveUser> listMatching(String substring);
    
    /**
     * Evicts from the cache all entries whose {@link DatawaveUser#getName()} equals the supplied name
     */
    String evict(String name);
    
    /**
     * Evicts from the cache all entries whose {@link DatawaveUser#getName()} contains the supplied substring
     */
    String evictMatching(String substring);
    
    /**
     * Evicts all entries from the cache.
     */
    String evictAll();
}