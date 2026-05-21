package com.blueocean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blueocean.entity.KeywordHistory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface KeywordHistoryMapper extends BaseMapper<KeywordHistory> {

    @Insert("<script>" +
            "INSERT INTO history_keyword (word, status, reason, usage_count, create_time, update_time) VALUES " +
            "<foreach collection='words' item='w' separator=','>" +
            "(#{w.word}, #{w.status}, #{w.reason}, #{w.usageCount}, #{w.createTime}, #{w.updateTime})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE status=VALUES(status), reason=VALUES(reason), usage_count=usage_count+1, update_time=VALUES(update_time)" +
            "</script>")
    int batchUpsert(@Param("words") List<KeywordHistory> words);

    @Update("UPDATE history_keyword SET usage_count=usage_count+1, update_time=NOW() WHERE word=#{word}")
    int incrementCount(@Param("word") String word);
}
