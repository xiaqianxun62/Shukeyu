package com.yuyue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyue.dto.StatsOverviewResponse.DayCount;
import com.yuyue.entity.MatchGame;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MatchGameMapper extends BaseMapper<MatchGame> {

    /**
     * 起始时间之后按天统计对局数（首页数据概览用）。
     * 返回 MM-dd 与当日场次，service 侧补齐空天。
     */
    @Select("SELECT DATE_FORMAT(create_time, '%m-%d') AS `date`, COUNT(*) AS `count` "
            + "FROM match_game WHERE create_time >= #{start} "
            + "GROUP BY DATE(create_time) ORDER BY DATE(create_time)")
    List<DayCount> countByDaySince(@Param("start") LocalDateTime start);
}
