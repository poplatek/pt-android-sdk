/*
 *  Rate limiter interface.
 */

package com.poplatek.pt.android.util;

import java.util.concurrent.Future;

public interface RateLimiter {
    void consumeSync(long count);
    Future<Void> consumeAsync(long count);
}
