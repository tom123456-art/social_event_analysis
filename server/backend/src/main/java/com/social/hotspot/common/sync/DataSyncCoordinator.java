package com.social.hotspot.common.sync;

import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

// 注册为 Spring 通用组件。
@Component
/** 串行执行受协调的同步任务，缓存处理由调用方完成。 */
// 定义数据同步任务协调器类。
public class DataSyncCoordinator {
    // 创建按等待顺序分配的可重入锁。
    private final ReentrantLock lock = new ReentrantLock(true);

    // 加锁执行同步任务，正常或异常结束都释放锁。
    public <T> T execute(Callable<T> action) throws Exception {
        // 等待取得锁，等待中允许响应线程中断。
        lock.lockInterruptibly();
        try {
            // 执行传入的同步任务并返回结果。
            return action.call();
        // 无论成功还是异常，都执行以下收尾步骤。
        } finally {
            // 释放同步任务锁，让后续任务继续执行。
            lock.unlock();
        }
    }
}
