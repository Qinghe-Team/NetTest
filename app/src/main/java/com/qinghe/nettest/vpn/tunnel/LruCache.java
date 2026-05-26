package com.qinghe.nettest.vpn.tunnel;

import java.util.LinkedHashMap;
import java.util.Map;

public class LruCache<K, V> extends LinkedHashMap<K, V> {
    public interface CleanupCallback<K, V> {
        void cleanup(Map.Entry<K, V> eldest);
    }

    private final int maxSize;
    private final CleanupCallback<K, V> callback;

    public LruCache(int maxSize, CleanupCallback<K, V> callback) {
        super(maxSize + 1, 1.0f, true);
        this.maxSize = maxSize;
        this.callback = callback;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        if (size() > maxSize) {
            if (callback != null) {
                callback.cleanup(eldest);
            }
            return true;
        }
        return false;
    }
}
