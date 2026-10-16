package com.social.hotspot.service.review;

import com.social.hotspot.service.etl.EtlWorkflowService;
import com.social.hotspot.cache.AnalyticsCache;
import com.social.hotspot.common.sync.DataSyncCoordinator;
import com.social.hotspot.config.ProjectPaths;
import com.social.hotspot.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

@Service
public class ReviewApplicationService {
    // 保存审核记录并负责状态流转。
    private final DataQualityReviewStore store;
    // 负责把审核快照写入临时输入数据。
    private final ReviewInputPreparationService pipeline;
    // 负责触发远程 ETL 全量重建。
    private final EtlWorkflowService remote;
    // 串行协调数据库写入和分析任务，避免并发快照冲突。
    private final DataSyncCoordinator coordinator;
    // 任务成功后清理分析缓存。
    private final AnalyticsCache cache;
    // 保存最近一次审核应用任务的可查询状态。
    private volatile Map<String, Object> latestJob = Map.of();
    // 标记是否已有审核应用任务运行。
    private boolean applying;
    // 使用单线程执行器保证审核应用任务按顺序执行。
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        // 创建后台线程并设置为守护线程。
        Thread thread = new Thread(r, "review-application"); thread.setDaemon(true); return thread;
    });

    public ReviewApplicationService(DataQualityReviewStore store, ReviewInputPreparationService pipeline, EtlWorkflowService remote,
                                    DataSyncCoordinator coordinator, AnalyticsCache cache) {
        // 保存审核应用流程所需的依赖。
        this.store = store; this.pipeline = pipeline; this.remote = remote; this.coordinator = coordinator; this.cache = cache;
    }

    // 预留待应用审核记录并异步提交重建任务。
    public synchronized Map<String, Object> submit() {
        // 同一时间只允许存在一个应用任务。
        if (applying) throw new BusinessException("已有审核应用任务正在运行");
        // 固定本次任务使用的审核记录快照。
        var snapshot = store.reserveForApplication();
        // 创建任务编号和排队状态。
        Map<String, Object> job = Map.of("id", UUID.randomUUID().toString(), "status", "QUEUED", "count", snapshot.size());
        // 发布最近任务状态并设置运行标记。
        latestJob = job;
        applying = true;
        // 将耗时的 ETL 重建提交到后台线程。
        try { worker.submit(() -> run(snapshot)); }
        catch (RuntimeException e) {
            try {
                // 释放已预留记录并记录启动失败状态。
                store.finishApplication(snapshot, null, "无法启动后台任务，请重试");
                updateJob("FAILED", null, "无法启动后台任务，请重试");
            } finally { applying = false; }
            throw e;
        }
        // 返回排队任务信息供前端轮询。
        return job;
    }

    // 返回最近一次任务状态。
    public Map<String, Object> latestJob() { return latestJob; }

    // 按任务结果更新状态、批次号和错误信息。
    private void updateJob(String status, String batch, String error) {
        // 基于当前状态复制可变映射。
        var job = new LinkedHashMap<>(latestJob);
        job.put("status", status);
        if (batch != null) job.put("batch_id", batch);
        if (error != null) job.put("error_message", error);
        // 使用不可变快照发布最新状态。
        latestJob = Map.copyOf(job);
    }

    // 执行审核数据应用、全量重建和状态收尾。
    private void run(List<Map<String, Object>> snapshot) {
        try {
            // 在统一同步协调器中执行整个应用事务流程。
            coordinator.execute(() -> {
                // 标记任务已经开始运行。
                updateJob("RUNNING", null, null);
                // 定位原始 CSV 基线文件。
                var source = ProjectPaths.root().resolve("data/crawler/social_event_real.csv");
                // 临时注入审核快照并运行远程全量 ETL。
                var result = pipeline.withSnapshot(snapshot, () -> remote.runFull(source));
                // ETL 必须成功后才允许审核记录生效。
                if (!"SUCCESS".equals(result.get("status"))) throw new IllegalStateException("分析重建未成功");
                // 读取新生成的分析批次号。
                String batch = result.get("batch_id").toString();
                // 将审核记录标记为已应用。
                store.finishApplication(snapshot, batch, null);
                // 清理公开事件缓存，确保页面读取新结果。
                cache.evictEvent("public_rss_latest");
                try { remote.markFullBaseline(source, batch); }
                catch (Exception checkpoint) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("审核已生效，增量检查点未更新；后续任务将重建基线", checkpoint);
                }
                // 更新成功状态并返回 ETL 结果。
                updateJob("SUCCESS", batch, null);
                return result;
            });
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("审核结果应用失败", e);
            // 业务异常保留可读原因，其余异常使用统一提示。
            String error = e instanceof BusinessException ? e.getMessage() : "审核结果应用失败，请检查任务日志、修正数据后重试";
            updateJob("FAILED", null, error);
            store.finishApplication(snapshot, null, error);
        } finally {
            // 无论成功或失败都释放运行标记。
            synchronized (this) { applying = false; }
        }
    }

    // 应用停止时中断后台审核任务。
    @PreDestroy public void stop() { worker.shutdownNow(); }
}
