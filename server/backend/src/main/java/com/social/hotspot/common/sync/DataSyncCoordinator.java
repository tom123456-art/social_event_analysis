package com.social.hotspot.common.sync;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

@Component
/** 数据同步协调器：集中处理数据刷新后的缓存与状态同步。 */
public class DataSyncCoordinator {
    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T execute(Callable<T> action) throws Exception {
        lock.lockInterruptibly();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }
}
