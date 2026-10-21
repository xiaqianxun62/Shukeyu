package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.entity.MatchGame;
import com.yuyue.mapper.MatchGameMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对局（match_game）的唯一创建入口。
 *
 * <p>编排、轮排、手工上报三条链路都要往 match_game 落对阵，过去各写一遍
 * 「清掉旧空对阵 + new MatchGame + 六个字段初始化」，新增模块照抄时极易漏掉
 * settleStatus = SETTLE_PENDING，或误删已经录了比分、有战绩的对阵。
 *
 * <p>收敛后：创建归这里；读取 / 更新 / 撤销仍归对局域的 MatchService（它是对局的主人）。
 */
@Component
@RequiredArgsConstructor
public class MatchGameWriter {

    private final MatchGameMapper matchGameMapper;

    /** 是否已有录入比分的对阵。有战绩就不允许重排，否则会丢掉结果与积分流水 */
    public boolean hasScoredMatches(Long gameId) {
        return matchGameMapper.selectCount(new LambdaQueryWrapper<MatchGame>()
                .eq(MatchGame::getGameId, gameId)
                .ne(MatchGame::getWinner, Constants.WINNER_NONE)) > 0;
    }

    /** 清掉还没打出比分的空对阵，保留已录比分的战绩 */
    public void clearPending(Long gameId) {
        matchGameMapper.delete(new LambdaQueryWrapper<MatchGame>()
                .eq(MatchGame::getGameId, gameId)
                .eq(MatchGame::getWinner, Constants.WINNER_NONE));
    }

    /**
     * 落一场还没开打的对阵，返回自增 id。
     *
     * @param roundNo 轮排轮次，从 1 开始；非轮排生成传 0
     * @param court 场地号，从 1 开始；非轮排生成传 0
     */
    public Long save(Long gameId, int format, List<Long> teamA, List<Long> teamB,
                     int roundNo, int court) {
        MatchGame match = new MatchGame();
        match.setGameId(gameId);
        match.setFormat(format);
        match.setTeamA(join(teamA));
        match.setTeamB(join(teamB));
        match.setRoundNo(roundNo);
        match.setCourt(court);
        resetResult(match);
        matchGameMapper.insert(match);
        return match.getId();
    }

    /**
     * 落一场已出结果的对局（裁判手工上报），返回已带 id 的对象。
     *
     * <p>settleStatus 仍置待结算，由调用方紧接着调结算服务推进（结算内部有幂等保护）。
     */
    public MatchGame report(Long gameId, Integer format, List<Long> teamA, List<Long> teamB,
                            Integer scoreA, Integer scoreB, Integer winner) {
        MatchGame match = new MatchGame();
        match.setGameId(gameId);
        match.setFormat(format);
        match.setTeamA(join(teamA));
        match.setTeamB(join(teamB));
        match.setRoundNo(0);
        match.setCourt(0);
        match.setScoreA(scoreA == null ? 0 : scoreA);
        match.setScoreB(scoreB == null ? 0 : scoreB);
        match.setWinner(winner == null ? Constants.WINNER_NONE : winner);
        resetResult(match);
        matchGameMapper.insert(match);
        return match;
    }

    /** 未开打的对阵统一初始化：比分 0、胜方未定、待结算 */
    private void resetResult(MatchGame match) {
        match.setScoreA(0);
        match.setScoreB(0);
        match.setWinner(Constants.WINNER_NONE);
        match.setSettleStatus(Constants.SETTLE_PENDING);
    }

    /** 队员 id 拼成逗号分隔字符串（team_a / team_b 的存储格式） */
    public static String join(Iterable<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }
}