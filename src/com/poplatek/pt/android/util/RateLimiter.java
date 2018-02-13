package com.poplatek.pt.android.util;

import java.util.concurrent.Future;

public interface RateLimiter {
    public void consumeSync(long count);
    public Future<Void> consumeAsync(long count);
}
