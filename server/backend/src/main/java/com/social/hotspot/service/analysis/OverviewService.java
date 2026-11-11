package com.social.hotspot.service.analysis;

import java.util.List;
import java.util.Map;

/** 热点门户业务接口，实现位于 OverviewServiceImpl。 */
public interface OverviewService {
    /** 热点门户：返回顶部指标、关注内容、批次来源、来源贡献、热点分类和关键词热度。 */
    Map<String, Object> overview(String eventId);
}
