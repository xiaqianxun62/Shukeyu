package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.dto.MatchReportRequest;
import com.yuyue.dto.MatchScoreRequest;
import com.yuyue.dto.MatchScoreResponse;
import com.yuyue.entity.Game;
import com.yuyue.entity.MatchGame;
import com.yuyue.entity.MatchScoreLog;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.GameMapper;
import com.yuyue.mapper.MatchGameMapper;
import com.yuyue.mapper.MatchScoreLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.StringJoiner;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchService {

    private final MatchGameMapper matchGameMapper;
    private final GameMapper gameMapper;
    private final MatchSettleService matchSettleService;
    private final MatchScoreLogMapper matchScoreLogMapper;

    /**
     * 上报对局结果：落库对局记录，并同步完成 ELO 积分更新 + 榜单刷新。
     */
    @Transactional
    public Long report(MatchReportRequest req, Long operatorId) {
        if (req.getTeamA().size() != req.getTeamB().size()
                || (req.getTeamA().size() != 1 && req.getTeamA().size() != 2)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "单打每队 1 人，双打每队 2 人");
        }

        MatchGame match = new MatchGame();
        match.setGameId(req.getGameId());
        match.setFormat(req.getFormat());
        match.setTeamA(join(req.getTeamA()));
        match.setTeamB(join(req.getTeamB()));
        match.setScoreA(req.getScoreA());
        match.setScoreB(req.getScoreB());
        match.setWinner(req.getWinner());
        match.setSettleStatus(Constants.SETTLE_PENDING);
        matchGameMapper.insert(match);

        // 计分留痕：上报即视为该用户首次计分（裁判）
        writeScoreLog(match, operatorId, Constants.SCORE_ACTION_FIRST, null, null);

        matchSettleService.settle(match, req.getWinner());

        log.info("对局上报: match={}, game={}, winner={}", match.getId(), req.getGameId(), req.getWinner());
        return match.getId();
    }

    /**
     * 现场计分：给编排出来的一场对阵录入比分。
     *
     * <p>胜方由比分自动判定（羽毛球不会平局）。只有「首次出结果」才同步结算积分，
     * 后面改比分不会重复加积分（结算服务内部按 settle_status 幂等，
     * 这里再用 winner 是否已定加一道保险）。
     *
     * <p>该球局所有对阵都有结果后，球局自动转为已结束。
     *
     * <p>每次提交都写入 match_score_log 留痕：首次提交者记为本场裁判，
     * 之后的每次改分记录修改人与改前/改后比分。
     */
    @Transactional
    public MatchScoreResponse score(Long matchId, MatchScoreRequest req, Long operatorId) {
        MatchGame match = matchGameMapper.selectById(matchId);
        if (match == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "对局不存在");
        }
        if (req.getScoreA().intValue() == req.getScoreB().intValue()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "比分不能相同，羽毛球没有平局");
        }

        boolean first = match.getWinner() == null || match.getWinner() == 0;
        int winner = req.getScoreA() > req.getScoreB() ? Constants.WINNER_A : Constants.WINNER_B;
        Integer prevScoreA = match.getScoreA();
        Integer prevScoreB = match.getScoreB();
        match.setScoreA(req.getScoreA());
        match.setScoreB(req.getScoreB());
        match.setWinner(winner);
        matchGameMapper.updateById(match);

        // 计分留痕：首次提交=裁判计分，之后=修改比分（带改前比分）
        writeScoreLog(match, operatorId,
                first ? Constants.SCORE_ACTION_FIRST : Constants.SCORE_ACTION_EDIT,
                first ? null : prevScoreA, first ? null : prevScoreB);

        if (first) {
            // 同步结算，立即落库 ELO；已结算的对局会被幂等拦掉，不会重复加分
            matchSettleService.settle(match, winner);
        }

        boolean gameFinished = finishGameIfDone(match.getGameId());
        log.info("计分: match={}, {} vs {}, winner={}", matchId, req.getScoreA(), req.getScoreB(), winner);
        return MatchScoreResponse.builder()
                .matchId(matchId)
                .scoreA(req.getScoreA())
                .scoreB(req.getScoreB())
                .winner(winner)
                .gameFinished(gameFinished)
                .build();
    }

    /** 写计分日志：记录谁提交了比分、改分时的改前比分 */
    private void writeScoreLog(MatchGame match, Long operatorId, int action,
                               Integer prevScoreA, Integer prevScoreB) {
        if (operatorId == null) {
            return;
        }
        MatchScoreLog scoreLog = new MatchScoreLog();
        scoreLog.setMatchId(match.getId());
        scoreLog.setGameId(match.getGameId());
        scoreLog.setOperatorId(operatorId);
        scoreLog.setAction(action);
        scoreLog.setScoreA(match.getScoreA());
        scoreLog.setScoreB(match.getScoreB());
        scoreLog.setPrevScoreA(prevScoreA);
        scoreLog.setPrevScoreB(prevScoreB);
        matchScoreLogMapper.insert(scoreLog);
    }

    /** 所有对阵都出了结果 → 球局结束；否则保持已编排 */
    private boolean finishGameIfDone(Long gameId) {
        long pending = matchGameMapper.selectCount(new LambdaQueryWrapper<MatchGame>()
                .eq(MatchGame::getGameId, gameId)
                .eq(MatchGame::getWinner, 0));
        if (pending > 0) {
            return false;
        }
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            return false;
        }
        if (game.getStatus() == null || game.getStatus() < Constants.GAME_STATUS_FINISHED) {
            game.setStatus(Constants.GAME_STATUS_FINISHED);
            gameMapper.updateById(game);
            log.info("球局已结束（全部对阵计分完成）: game={}", gameId);
        }
        return true;
    }

    private String join(Iterable<Long> ids) {
        StringJoiner sj = new StringJoiner(",");
        ids.forEach(id -> sj.add(String.valueOf(id)));
        return sj.toString();
    }
}
