package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {
    @Select("select event_id, event_name, description, start_time, end_time, status, created_at from event_info order by created_at desc")
    List<Map<String, Object>> events();

    @Select("select * from ads_event_overview where event_id = #{eventId}")
    Map<String, Object> overview(@Param("eventId") String eventId);

    @Select("select * from ads_event_heat_trend where event_id = #{eventId} order by time_bucket asc")
    List<Map<String, Object>> heatTrend(@Param("eventId") String eventId);

    @Select("select * from ads_platform_spread_timeline where event_id = #{eventId} order by first_publish_time asc")
    List<Map<String, Object>> platformTimeline(@Param("eventId") String eventId);

    @Select("select * from ads_interaction_summary where event_id = #{eventId} order by hot_score desc")
    List<Map<String, Object>> interaction(@Param("eventId") String eventId);

    @Select("select * from ads_keyword_rank where event_id = #{eventId} order by word_count desc limit #{limit}")
    List<Map<String, Object>> keywordRank(@Param("eventId") String eventId, @Param("limit") int limit);

    @Select("select * from ads_sentiment_trend where event_id = #{eventId} order by time_bucket asc, sentiment_label asc")
    List<Map<String, Object>> sentimentTrend(@Param("eventId") String eventId);

    @Select("select * from ads_content_hot_rank where event_id = #{eventId} order by rank_no asc limit #{limit}")
    List<Map<String, Object>> contentRank(@Param("eventId") String eventId, @Param("limit") int limit);

    @Select("select * from ads_content_hot_rank where event_id = #{eventId} order by publish_time desc, id desc limit #{limit}")
    List<Map<String, Object>> realPublicContents(@Param("eventId") String eventId, @Param("limit") int limit);

    @Select("""
            select count(*) topic_count
            from (
              select trim(title) topic
              from ads_content_hot_rank
              where event_id = #{eventId} and trim(coalesce(title, '')) <> ''
              group by trim(title)
            ) topics
            """)
    Map<String, Object> topicCount(@Param("eventId") String eventId);

    @Select("""
            select trim(title) topic, count(*) content_count, count(distinct platform) platform_count,
                   min(publish_time) first_publish_time, max(publish_time) latest_publish_time,
                   sum(coalesce(hot_score, 0)) hot_score
            from ads_content_hot_rank
            where event_id = #{eventId} and trim(coalesce(title, '')) <> ''
            group by trim(title)
            order by hot_score desc, content_count desc
            limit #{limit}
            """)
    List<Map<String, Object>> topicRank(@Param("eventId") String eventId, @Param("limit") int limit);

    @Select("""
            select count(*) content_count,
                   count(distinct nullif(trim(author_id), '')) author_count,
                   sum(case when nullif(trim(author_name), '') is not null then 1 else 0 end) author_name_count,
                   sum(case when nullif(trim(location), '') is not null then 1 else 0 end) location_count,
                   sum(case when nullif(trim(user_age_group), '') is not null then 1 else 0 end) age_count,
                   sum(case when nullif(trim(user_gender), '') is not null then 1 else 0 end) gender_count
            from ads_content_hot_rank
            where event_id = #{eventId}
            """)
    Map<String, Object> profileCoverage(@Param("eventId") String eventId);

    @Select("""
            select parent.platform source_platform, child.platform target_platform, count(*) link_count
            from ads_content_hot_rank child
            join ads_content_hot_rank parent
              on parent.event_id = child.event_id and parent.content_id = child.parent_content_id
            where child.event_id = #{eventId} and nullif(trim(child.parent_content_id), '') is not null
            group by parent.platform, child.platform
            order by link_count desc
            """)
    List<Map<String, Object>> propagationLinks(@Param("eventId") String eventId);

    @Select("""
            with topic_platform as (
              select event_id, platform, trim(title) title
              from ads_content_hot_rank
              where event_id = #{eventId} and nullif(trim(title), '') is not null
              group by event_id, platform, trim(title)
            )
            select left_topic.platform source_platform, right_topic.platform target_platform,
                   count(*) link_count
            from topic_platform left_topic
            join topic_platform right_topic
              on left_topic.event_id = right_topic.event_id
             and left_topic.platform < right_topic.platform
             and left_topic.title = right_topic.title
            group by left_topic.platform, right_topic.platform
            order by link_count desc
            """)
    List<Map<String, Object>> topicPropagationLinks(@Param("eventId") String eventId);

    @Select("select * from etl_batch where event_id = #{eventId} order by started_at desc limit 1")
    Map<String, Object> latestBatch(@Param("eventId") String eventId);

    @Select("select * from ads_noise_summary where event_id = #{eventId} order by time_bucket asc")
    List<Map<String, Object>> noiseSummary(@Param("eventId") String eventId);

    @Select("select * from etl_batch order by started_at desc limit #{limit}")
    List<Map<String, Object>> batches(@Param("limit") int limit);

    @Select("select * from etl_task_log where batch_id = #{batchId} order by started_at asc")
    List<Map<String, Object>> taskLogs(@Param("batchId") String batchId);

    @Select("select count(*) event_count from event_info")
    Map<String, Object> eventCount();
}
