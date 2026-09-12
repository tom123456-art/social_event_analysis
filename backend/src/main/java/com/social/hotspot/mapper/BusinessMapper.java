package com.social.hotspot.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface BusinessMapper {
    @Select("select * from app_user order by id asc")
    List<Map<String, Object>> users();

    @Select("select * from app_user where id = #{id}")
    Map<String, Object> userById(@Param("id") Long id);

    @Insert("""
            insert into app_user(username, nickname, phone, email, role, status, bio)
            values(#{item.username}, #{item.nickname}, #{item.phone}, #{item.email}, #{item.role}, #{item.status}, #{item.bio})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    int insertUser(@Param("item") Map<String, Object> item);

    @Update("""
            update app_user set
              nickname = #{item.nickname},
              phone = #{item.phone},
              email = #{item.email},
              role = #{item.role},
              status = #{item.status},
              bio = #{item.bio}
            where id = #{id}
            """)
    int updateUser(@Param("id") Long id, @Param("item") Map<String, Object> item);

    @Delete("delete from app_user where id = #{id} and username <> 'admin'")
    int deleteUser(@Param("id") Long id);

    @Select("select * from app_user where username = #{username} limit 1")
    Map<String, Object> userByUsername(@Param("username") String username);

    @Select("""
            select ui.*, ei.event_name
            from user_interaction ui
            left join event_info ei on ui.event_id = ei.event_id
            order by ui.created_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> interactions(@Param("limit") int limit);

    @Select("select * from user_interaction where username = #{username} order by created_at desc limit #{limit}")
    List<Map<String, Object>> userInteractions(@Param("username") String username, @Param("limit") int limit);

    @Insert("""
            insert into user_interaction(user_id, username, event_id, content_id, platform, action_type, comment_text, sentiment_label, status)
            values(#{item.user_id}, #{item.username}, #{item.event_id}, #{item.content_id}, #{item.platform}, #{item.action_type},
                   #{item.comment_text}, #{item.sentiment_label}, 'NORMAL')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    int insertInteraction(@Param("item") Map<String, Object> item);

    @Delete("delete from user_interaction where id = #{id}")
    int deleteInteraction(@Param("id") Long id);

    @Select("""
            select us.*, ei.event_name
            from user_submission us
            left join event_info ei on us.event_id = ei.event_id
            order by us.created_at desc
            limit #{limit}
            """)
    List<Map<String, Object>> submissions(@Param("limit") int limit);

    @Select("select * from user_submission where username = #{username} order by created_at desc limit #{limit}")
    List<Map<String, Object>> userSubmissions(@Param("username") String username, @Param("limit") int limit);

    @Insert("""
            insert into user_submission(user_id, username, event_id, platform, title, content_text, location, status)
            values(#{item.user_id}, #{item.username}, #{item.event_id}, #{item.platform}, #{item.title}, #{item.content_text},
                   #{item.location}, 'PENDING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "item.id")
    int insertSubmission(@Param("item") Map<String, Object> item);

    @Update("update user_submission set status = #{status} where id = #{id}")
    int updateSubmissionStatus(@Param("id") Long id, @Param("status") String status);

    @Delete("delete from user_submission where id = #{id}")
    int deleteSubmission(@Param("id") Long id);

    @Select("select * from ads_content_hot_rank where event_id = #{eventId} order by rank_no asc limit #{limit}")
    List<Map<String, Object>> contents(@Param("eventId") String eventId, @Param("limit") int limit);

}
