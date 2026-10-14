package com.yuyue.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yuyue.entity.Tournament;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TournamentMapper extends BaseMapper<Tournament> {
}