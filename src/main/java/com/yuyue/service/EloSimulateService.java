package com.yuyue.service;

import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.config.EloProperties;
import com.yuyue.dto.EloSimulateRequest;
import com.yuyue.dto.EloSimulateResponse;
import com.yuyue.engine.ArrangeEngine;
import com.yuyue.engine.EloCalculator;
import com.yuyue.exception.BizException;
import com.yuyue.util.MatchFormatUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ELO 试算：复用真实编排引擎与 ELO 计算器，输入人数与积分即可看到编排方案和积分变化。
 * <p>
 * 纯计算，不读库不写库，也不会改动任何真实积分。
 */
@Service
@RequiredArgsConstructor
public class EloSimulateService {

    private final ArrangeEngine arrangeEngine;
    private final EloCalculator eloCalculator;
    private final EloProperties eloProperties;

    public EloSimulateResponse simulate(EloSimulateRequest req) {
        int males = Math.max(req.getMaleCount(), 0);
        int females = Math.max(req.getFemaleCount(), 0);

        // userId 用 1..n 的序号占位，label 用「男1/女1」便于前端展示
        List<ArrangeEngine.Player> players = new ArrayList<>();
        Map<Long, ArrangeEngine.Player> byId = new LinkedHashMap<>();
        for (int i = 0; i < males; i++) {
            addPlayer(players, byId, (long) (i + 1), Constants.GENDER_MALE, req, i);
        }
        for (int j = 0; j < females; j++) {
            addPlayer(players, byId, (long) (males + j + 1), Constants.GENDER_FEMALE, req, males + j);
        }

        boolean roundRobin = Boolean.TRUE.equals(req.getRoundRobin());

        // 赛制（单打/男双/女双/混双）优先转成对应的编排方案；指定了方案则直接用方案
        Integer schemeId = req.getSchemeId();
        if (schemeId == null && req.getFormat() != null) {
            schemeId = schemeOfFormat(req.getFormat(), males, females);
        }

        // 指定了方案就按方案编排，否则按性别构成自动过滤
        ArrangeEngine.ArrangeResult arranged = schemeId == null
                ? arrangeEngine.arrange(players, roundRobin)
                : arrangeEngine.arrangeByScheme(players, schemeId, roundRobin);

        // 当前积分：循环赛里同一个人要打多场，逐场累计（下一场以上一场赛后分为起点）
        Map<Long, Integer> current = new LinkedHashMap<>();
        players.forEach(p -> current.put(p.userId(), p.rating()));

        List<EloSimulateResponse.SimMatch> matches = new ArrayList<>();
        for (int i = 0; i < arranged.matches().size(); i++) {
            ArrangeEngine.ArrangeMatch m = arranged.matches().get(i);
            int winner = winnerOf(req, i);

            EloCalculator.TeamResult[] settled = eloCalculator.settle(
                    inputs(m.teamA(), current, req.getGamesPlayed(), byId),
                    inputs(m.teamB(), current, req.getGamesPlayed(), byId),
                    winner);

            List<EloSimulateResponse.SimSide> sideA = toSide(settled[0], byId, males);
            List<EloSimulateResponse.SimSide> sideB = toSide(settled[1], byId, males);
            for (EloSimulateResponse.SimSide s : sideA) {
                current.put(s.getId(), s.getRatingAfter());
            }
            for (EloSimulateResponse.SimSide s : sideB) {
                current.put(s.getId(), s.getRatingAfter());
            }

            matches.add(EloSimulateResponse.SimMatch.builder()
                    .format(m.format())
                    .formatName(formatName(m.format()))
                    .winner(winner)
                    .teamA(sideA)
                    .teamB(sideB)
                    .expectedA(settled[0].expected())
                    .expectedB(settled[1].expected())
                    .build());
        }

        List<EloSimulateResponse.SimPlayer> simPlayers = players.stream()
                .map(p -> EloSimulateResponse.SimPlayer.builder()
                        .id(p.userId())
                        .gender(p.gender())
                        .label(labelOf(p, males))
                        .rating(p.rating())
                        .finalRating(current.getOrDefault(p.userId(), p.rating()))
                        .gamesPlayed(req.getGamesPlayed())
                        .kFactor(eloCalculator.kFactor(req.getGamesPlayed()))
                        .build())
                .toList();

        return EloSimulateResponse.builder()
                .schemeId(arranged.schemeId())
                .schemeName(arranged.schemeName())
                .players(simPlayers)
                .matches(matches)
                .build();
    }

    /**
     * 赛制 → 编排方案：1 单打→全单打 2 男双→全男双 3 女双→全女双 4 混双→全混双。
     * 人数明显不满足时给出可读提示，免得试算出来是空的。
     */
    private int schemeOfFormat(int format, int males, int females) {
        switch (format) {
            case Constants.FORMAT_SINGLES:
                return 1;
            case Constants.FORMAT_MEN_DOUBLES:
                if (males < 4) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "男双需要至少 4 名男生，当前男生 " + males + " 人");
                }
                return 3;
            case Constants.FORMAT_WOMEN_DOUBLES:
                if (females < 4) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "女双需要至少 4 名女生，当前女生 " + females + " 人");
                }
                return 4;
            case Constants.FORMAT_MIXED_DOUBLES:
                if (males < 2 || females < 2) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "混双需要男女各至少 2 人，当前男 " + males + " 人、女 " + females + " 人");
                }
                if (males != females) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            "全混双要求男女人数相等，当前男 " + males + " 人、女 " + females + " 人");
                }
                return 2;
            default:
                throw new BizException(ErrorCode.PARAM_ERROR, "不支持的赛制: " + format);
        }
    }

    private void addPlayer(List<ArrangeEngine.Player> players, Map<Long, ArrangeEngine.Player> byId,
                           Long id, int gender, EloSimulateRequest req, int index) {
        ArrangeEngine.Player player = new ArrangeEngine.Player(id, gender, ratingOf(req, index));
        players.add(player);
        byId.put(id, player);
    }

    private int ratingOf(EloSimulateRequest req, int index) {
        List<Integer> ratings = req.getRatings();
        if (ratings == null || index >= ratings.size() || ratings.get(index) == null) {
            return eloProperties.getDefaultRating();
        }
        return ratings.get(index);
    }

    /** 用「当前积分」构造结算输入，循环赛下即为上一场打完后的分数 */
    private List<EloCalculator.PlayerInput> inputs(List<Long> ids, Map<Long, Integer> currentRatings,
                                                   int gamesPlayed, Map<Long, ArrangeEngine.Player> byId) {
        List<EloCalculator.PlayerInput> result = new ArrayList<>();
        for (Long id : ids) {
            Integer rating = currentRatings.get(id);
            if (rating != null) {
                // 带上性别：试算同样要走 ELO 性别修正，男双打女双/混双的期望才会与线上一致
                ArrangeEngine.Player p = byId.get(id);
                int gender = p == null ? Constants.GENDER_UNKNOWN : p.gender();
                result.add(new EloCalculator.PlayerInput(id, rating, gamesPlayed, gender));
            }
        }
        return result;
    }

    private List<EloSimulateResponse.SimSide> toSide(EloCalculator.TeamResult team,
                                                     Map<Long, ArrangeEngine.Player> byId, int males) {
        return team.players().stream()
                .map(r -> {
                    ArrangeEngine.Player p = byId.get(r.userId());
                    return EloSimulateResponse.SimSide.builder()
                            .id(r.userId())
                            .label(p == null ? String.valueOf(r.userId()) : labelOf(p, males))
                            .ratingBefore(r.ratingBefore())
                            .delta(r.delta())
                            .ratingAfter(r.ratingAfter())
                            .kFactor(r.kFactor())
                            .expected(r.expected())
                            .build();
                })
                .toList();
    }

    /** 展示名：男1 / 女1（序号按性别各自从 1 开始，男在前女在后） */
    private String labelOf(ArrangeEngine.Player p, int males) {
        if (p.gender() == Constants.GENDER_MALE) {
            return "男" + p.userId();
        }
        return "女" + (p.userId() - males);
    }

    /** 胜方：未指定或越界时默认 A 队胜 */
    private int winnerOf(EloSimulateRequest req, int index) {
        List<Integer> winners = req.getWinners();
        if (winners == null || index >= winners.size() || winners.get(index) == null) {
            return Constants.WINNER_A;
        }
        int w = winners.get(index);
        return w == Constants.WINNER_B ? Constants.WINNER_B : Constants.WINNER_A;
    }

    /** 赛制文案统一走 MatchFormatUtil，避免与 RotationService / TournamentService 各写一份 */
    private String formatName(int format) {
        return MatchFormatUtil.formatName(format);
    }
}
