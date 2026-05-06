package com.blueocean.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blueocean.entity.TrendKeyword;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface TrendKeywordMapper extends BaseMapper<TrendKeyword> {

    @Insert("<script>" +
            "INSERT INTO trend_keywords (word, usage_count, burst_months, layout_months, remark, create_time, update_time) VALUES " +
            "<foreach collection='words' item='w' separator=','>" +
            "(#{w.word}, #{w.usageCount}, #{w.burstMonths}, #{w.layoutMonths}, #{w.remark}, #{w.createTime}, #{w.updateTime})" +
            "</foreach>" +
            " ON DUPLICATE KEY UPDATE usage_count=usage_count+1, burst_months=VALUES(burst_months), layout_months=VALUES(layout_months), remark=VALUES(remark), update_time=VALUES(update_time)" +
            "</script>")
    int batchUpsert(@Param("words") List<TrendKeyword> words);
}
