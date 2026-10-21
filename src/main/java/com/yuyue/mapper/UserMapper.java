package com.yuyue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyue.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * ELO 分布：按综合 rating 分四段统计人数（首页数据概览用）。
     * bucket：0 &lt;1000、1 1000-1199、2 1200-1399、3 ≥1400；cnt 为该段人数。
     * 逻辑删除由 @TableLogic 自动过滤。
     */
    @Select("SELECT CASE WHEN rating < 1000 THEN 0 WHEN rating < 1200 THEN 1 "
            + "WHEN rating < 1400 THEN 2 ELSE 3 END AS bucket, COUNT(*) AS cnt "
            + "FROM `user` GROUP BY bucket")
    List<Map<String, Object>> ratingBucketCounts();
}
