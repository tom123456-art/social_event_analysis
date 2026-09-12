package com.social.hotspot.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

@Component
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
