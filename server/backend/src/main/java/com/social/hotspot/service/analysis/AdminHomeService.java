package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 后台首页业务接口，实现位于 AdminHomeServiceImpl。 */
public interface AdminHomeService {
    /** 后台首页：返回顶部指标、批次状态及热度排行、内容审核、质量巡检数据。 */
    Map<String, Object> adminOverview(String eventId);
}
