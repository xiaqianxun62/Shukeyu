package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.dto.ArrangeRequest;
import com.yuyue.dto.ArrangeResponse;
import com.yuyue.dto.GameCreateRequest;
import com.yuyue.dto.GameMatchesResponse;
import com.yuyue.dto.GameRegisterRequest;
import com.yuyue.dto.GameResponse;
import com.yuyue.engine.ArrangeEngine;
import com.yuyue.engine.ArrangeEngine.ArrangeResult;
import com.yuyue.engine.ArrangeEngine.Player;
import com.yuyue.entity.Game;
import com.yuyue.entity.MatchGame;
import com.yuyue.entity.MatchScoreLog;
import com.yuyue.entity.Registration;
import com.yuyue.entity.User;
import com.yuyue.util.MatchFormatUtil;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.GameMapper;
import com.yuyue.mapper.MatchGameMapper;
import com.yuyue.mapper.MatchScoreLogMapper;
import com.yuyue.mapper.RegistrationMapper;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.yuyue.web.UserContext;

@Slf4j
@Service
@RequiredArgsConstructor
public class GameService {

    private final GameMapper gameMapper;
    private final RegistrationMapper registrationMapper;
    private final MatchGameMapper matchGameMapper;
    private final MatchGameWriter matchGameWriter;
    private final MatchScoreLogMapper matchScoreLogMapper;
    private final UserMapper userMapper;
    private final com.yuyue.mapper.CourtMapper courtMapper;
    private final StringRedisTemplate redisTemplate;
    private final ArrangeEngine arrangeEngine;
    private final RegistrationSnapshotService snapshotService;

    /**
     * 发布球局。两种模式：
     * 预报名（默认）必须给定日期与开始时间；现场报名只要标题 + 人数 + 场地数，
     * 时间地点留空，等人报满后由发起人选编排方案、现场开打。
     */
    public GameResponse create(Long creatorId, GameCreateRequest req) {
        Game game = new Game();
        applyCreateRequest(game, req);
        game.setStatus(Constants.GAME_STATUS_OPEN);
        game.setCreatorId(creatorId);
        // 前置查重（业务层主动提示）+ DuplicateKeyException 兜底（并发竞态被唯一键挡下）
        checkTitleUnique(null, game.getTitle());
        try {
            gameMapper.insert(game);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.GAME_TITLE_DUPLICATE);
        }
        log.info("球局发布: id={}, title={}, mode={}, creator={}",
                game.getId(), game.getTitle(), game.getMode(), creatorId);
        return buildGameResponse(game);
    }

    /**
     * 编辑球局：仅发起人可编辑（管理员例外），且仅限 status=0（报名中）状态。
     * 不能修改 status / creatorId / schemeId。
     */
    @Transactional
    public GameResponse update(Long operatorId, Long gameId, GameCreateRequest req) {
        Game game = requireGame(gameId);
        if (!isAdmin(operatorId) && !Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有发起人可以编辑球局");
        }
        if (game.getStatus() != Constants.GAME_STATUS_OPEN) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR, "已编排或已结束的球局不能修改");
        }
        applyCreateRequest(game, req);
        // 编辑场景：排除自己 id 后标题唯一
        checkTitleUnique(gameId, game.getTitle());
        try {
            gameMapper.updateById(game);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.GAME_TITLE_DUPLICATE);
        }
        log.info("球局编辑: id={}, title={}, operator={}, admin={}",
                gameId, game.getTitle(), operatorId, isAdmin(operatorId));
        return buildGameResponse(game);
    }

    /**
     * 删除球局：仅发起人可删除（管理员例外），且仅限 status=0（报名中）状态。
     * 级联软删该球局下所有报名记录（Game / Registration 都有 @TableLogic），
     * 同时清理 Redis 里的报名计数缓存。
     */
    @Transactional
    public void delete(Long operatorId, Long gameId) {
        Game game = requireGame(gameId);
        if (!isAdmin(operatorId) && !Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有发起人可以删除球局");
        }
        if (game.getStatus() != Constants.GAME_STATUS_OPEN) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR, "已编排或已结束的球局不能删除");
        }
        // 级联软删报名记录
        registrationMapper.delete(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getGameId, gameId));
        // 清 Redis 报名计数
        redisTemplate.opsForHash().delete(Constants.GAME_REG_COUNT, String.valueOf(gameId));
        // 软删球局本身
        gameMapper.deleteById(gameId);
        log.info("球局删除: id={}, title={}, operator={}, admin={}",
                gameId, game.getTitle(), operatorId, isAdmin(operatorId));
    }

    /** 判断 operatorId 对应用户是否管理员（user.isAdmin == 1） */
    private boolean isAdmin(Long operatorId) {
        if (operatorId == null) return false;
        com.yuyue.entity.User u = userMapper.selectById(operatorId);
        return u != null && u.getIsAdmin() != null && u.getIsAdmin() == 1;
    }

    /** 把 GameCreateRequest 的字段落到 game 上，并做模式分支校验（预报名必填日期时间） */
    private void applyCreateRequest(Game game, GameCreateRequest req) {
        int mode = req.getMode() == null ? Constants.GAME_MODE_PREBOOK : req.getMode();
        if (mode != Constants.GAME_MODE_PREBOOK && mode != Constants.GAME_MODE_ONSITE) {
            throw new BizException(ErrorCode.PARAM_ERROR, "球局模式不合法（0 预报名 / 1 现场报名）");
        }
        game.setTitle(req.getTitle());
        game.setMode(mode);
        game.setLocation(req.getLocation());
        game.setCourtId(req.getCourtId());
        game.setRemark(req.getRemark() == null ? null : req.getRemark().trim());
        game.setCover(req.getCover());
        game.setMaxPlayers(req.getMaxPlayers());
        game.setCourtCount(req.getCourtCount() == null ? 2 : req.getCourtCount());
        game.setIsVerified(req.getIsVerified() != null && req.getIsVerified() ? 1 : 0);
        if (mode == Constants.GAME_MODE_PREBOOK) {
            if (req.getPlayDate() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "打球日期不能为空");
            }
            if (req.getStartTime() == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, "开始时间不能为空");
            }
            if (req.getPlayDate().isBefore(LocalDate.now())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "打球日期不能早于今天");
            }
            game.setPlayDate(req.getPlayDate());
            game.setStartTime(req.getStartTime());
            game.setEndTime(req.getEndTime());
        } else {
            // 现场报名：日期直接取当天（人齐即打），开始/结束时间留空待现场确认
            game.setPlayDate(LocalDate.now());
            game.setStartTime(null);
            game.setEndTime(null);
        }
    }

    /**
     * 球局列表：status 先分组（报名中 → 已编排 → 已结束 → 已取消），
     * 组内按 playDate 升序（最近的排最前）。
     * 认证可见性过滤：未登录 / 非认证用户只返回 is_verified=0 的球局；
     * 认证用户返回全部（is_verified=1 的认证球局 + is_verified=0 的公开球局）。
     */
    public List<GameResponse> list() {
        LambdaQueryWrapper<Game> qw = new LambdaQueryWrapper<Game>()
                .eq(Game::getHidden, 0);
        // 认证过滤：看当前登录用户身份
        Long userId = UserContext.get();
        boolean isVerifiedUser = userId != null && isVerifiedUser(userId);
        if (!isVerifiedUser) {
            // 非认证用户 / 未登录：只能看公开球局
            qw.eq(Game::getIsVerified, 0);
        }
        return gameMapper.selectList(qw
                        .orderByAsc(Game::getStatus)
                        .orderByAsc(Game::getPlayDate)
                        .orderByAsc(Game::getStartTime))
                .stream().map(this::buildGameResponse).toList();
    }

    /** 判断用户是否通过 SHUKE 认证 */
    private boolean isVerifiedUser(Long userId) {
        User u = userMapper.selectById(userId);
        return u != null && u.getIsVerified() != null && u.getIsVerified() == 1;
    }

    /** 发起人 / 管理员：隐藏球局（不再首页显示，但详情页仍可访问） */
    public void hide(Long gameId, Long operatorId) {
        Game game = requireGame(gameId);
        if (!isAdmin(operatorId) && !Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无权限隐藏该球局");
        }
        game.setHidden(1);
        gameMapper.updateById(game);
    }

    /** 发起人 / 管理员：恢复显示 */
    public void unhide(Long gameId, Long operatorId) {
        Game game = requireGame(gameId);
        if (!isAdmin(operatorId) && !Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "无权限恢复该球局");
        }
        game.setHidden(0);
        gameMapper.updateById(game);
    }

    /** 当前用户参与过的所有球局（按 playDate 倒序） */
    public List<GameResponse> myGames(Long userId) {
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getUserId, userId));
        if (regs.isEmpty()) {
            return List.of();
        }
        List<Long> gameIds = regs.stream().map(Registration::getGameId).toList();
        return gameMapper.selectList(new LambdaQueryWrapper<Game>()
                        .in(Game::getId, gameIds)
                        .orderByDesc(Game::getPlayDate)
                        .orderByDesc(Game::getStartTime))
                .stream().map(this::buildGameResponse).toList();
    }

    /** 球局详情 */
    public GameResponse detail(Long gameId) {
        return buildGameResponse(requireGame(gameId));
    }

    /**
     * 报名：校验满员/重复，落库 + Redis 计数（统一实名报名）
     */
    @Transactional
    public GameResponse register(Long userId, Long gameId, GameRegisterRequest req) {
        // 行锁串行化同一球局的并发报名：在锁内重新读最新人数，彻底关闭「检查-再插入」竞态超员窗口
        Game game = gameMapper.selectByIdForUpdate(gameId);
        if (game == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "球局不存在");
        }
        if (game.getStatus() != Constants.GAME_STATUS_OPEN) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR);
        }
        if (registrationMapper.selectCount(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getGameId, gameId)
                .eq(Registration::getUserId, userId)) > 0) {
            throw new BizException(ErrorCode.ALREADY_REGISTERED);
        }

        long count = registrationMapper.selectCount(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));
        if (count >= game.getMaxPlayers()) {
            throw new BizException(ErrorCode.GAME_FULL);
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        if (user.getGender() == null || user.getGender() == 0) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先完善性别后再报名");
        }

        if (user.getName() == null || user.getName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请先完善姓名后再报名");
        }

        Registration reg = new Registration();
        reg.setGameId(gameId);
        reg.setUserId(userId);
        reg.setGender(user.getGender());
        reg.setRating(user.getRating());
        reg.setAnonymous(Constants.ANONYMOUS_NO);
        try {
            registrationMapper.insert(reg);
        } catch (DuplicateKeyException e) {
            // 数据库唯一约束兜底：锁窗口外的残留重复（如历史数据、无事务的调用方）
            throw new BizException(ErrorCode.ALREADY_REGISTERED);
        }

        // Redis 计数（计数器作热点读缓存）
        redisTemplate.opsForHash().increment(Constants.GAME_REG_COUNT,
                String.valueOf(gameId), 1);

        log.info("报名成功: game={}, user={}", gameId, userId);
        return buildGameResponse(game);
    }

    /**
     * 取消报名：仅「报名中」的球局可取消，软删除记录 + Redis 计数 -1
     */
    @Transactional
    public GameResponse cancelRegister(Long userId, Long gameId) {
        Game game = requireGame(gameId);
        if (game.getStatus() != Constants.GAME_STATUS_OPEN) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR, "球局已编排或已结束，无法取消报名");
        }
        Registration reg = registrationMapper.selectOne(new LambdaQueryWrapper<Registration>()
                .eq(Registration::getGameId, gameId)
                .eq(Registration::getUserId, userId));
        if (reg == null) {
            throw new BizException(ErrorCode.NOT_REGISTERED);
        }
        // 逻辑删除（@TableLogic：UPDATE deleted=1，保留记录用于审计）
        registrationMapper.deleteById(reg.getId());

        // Redis 计数 -1（计数与实际不一致时兜底到 0，不出现负数）
        Long left = redisTemplate.opsForHash()
                .increment(Constants.GAME_REG_COUNT, String.valueOf(gameId), -1);
        if (left != null && left < 0) {
            redisTemplate.opsForHash().put(Constants.GAME_REG_COUNT, String.valueOf(gameId), "0");
        }

        log.info("取消报名成功: game={}, user={}", gameId, userId);
        return buildGameResponse(game);
    }

    /**
     * 自动编排：读取报名人员，默认引擎按性别构成过滤方案；
     * 传 schemeId 时强制使用指定方案（如 2 = 全混双），roundRobin = 循环赛
     */
    @Transactional
    public ArrangeResponse arrange(Long operatorId, Long gameId, ArrangeRequest req) {
        Game game = requireGame(gameId);
        if (game.getStatus() != Constants.GAME_STATUS_OPEN) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR);
        }
        // 编排方案由发起人拍板（管理员例外）
        if (!isAdmin(operatorId) && !Objects.equals(game.getCreatorId(), operatorId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有发起人可以选择编排方案");
        }
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));
        if (regs.size() < 4) {
            throw new BizException(ErrorCode.PARAM_ERROR, "报名人数不足 4 人，无法编排");
        }

        // 自动编排同样要在生成前取一遍最新资料：报名快照里存的是报名那一刻的积分与性别，
        // 球友之后打过的比赛、改过的资料都不会回写快照
        Map<Long, User> userMap = loadUserMap(regs);
        int refreshed = snapshotService.refresh(regs, userMap);
        if (refreshed > 0) {
            log.info("自动编排前刷新报名快照: game={}, 更新 {} 人", gameId, refreshed);
        }
        List<Player> players = regs.stream()
                .map(r -> {
                    User u = userMap.get(r.getUserId());
                    return new Player(r.getUserId(),
                            snapshotService.genderOf(r, u),
                            snapshotService.ratingOf(r, u));
                })
                .toList();
        boolean roundRobin = req != null && Boolean.TRUE.equals(req.getRoundRobin());
        ArrangeResult result = req != null && req.getSchemeId() != null
                ? arrangeEngine.arrangeByScheme(players, req.getSchemeId(), roundRobin)
                : arrangeEngine.arrange(players, roundRobin);

        // 已经打出比分的对阵不能丢，有战绩就不许重排
        if (matchGameWriter.hasScoredMatches(gameId)) {
            throw new BizException(ErrorCode.GAME_STATUS_ERROR, "已有对阵录入比分，不能重新编排");
        }
        // 清掉上一次编排出来的空对阵，落这一批（创建细节统一走 MatchGameWriter）
        matchGameWriter.clearPending(gameId);
        for (ArrangeEngine.ArrangeMatch m : result.matches()) {
            matchGameWriter.save(gameId, m.format(), m.teamA(), m.teamB(), 0, 0);
        }

        game.setStatus(Constants.GAME_STATUS_ARRANGED);
        game.setSchemeId(result.schemeId());
        gameMapper.updateById(game);

        log.info("编排完成: game={}, scheme={}, 共 {} 场", gameId, result.schemeName(), result.matches().size());
        return ArrangeResponse.builder()
                .gameId(gameId)
                .schemeId(result.schemeId())
                .schemeName(result.schemeName())
                .roundRobin(roundRobin)
                .matches(result.matches().stream()
                        .map(m -> ArrangeResponse.MatchItem.builder()
                                .format(m.format())
                                .teamA(m.teamA())
                                .teamB(m.teamB())
                                .build())
                        .toList())
                .build();
    }

    /**
     * 球局对阵（编排结果 + 现场比分）：所有人可见，用来对着场地打球、录分。
     * 未登录访客只看到显示昵称。
     */
    public GameMatchesResponse matches(Long gameId) {
        Game game = requireGame(gameId);
        List<MatchGame> list = matchGameMapper.selectList(
                new LambdaQueryWrapper<MatchGame>()
                        .eq(MatchGame::getGameId, gameId)
                        .orderByAsc(MatchGame::getId));
        Map<Long, String> displayNames = displayNamesOf(gameId);
        // 两队的性别构成：两队可能不同（男双 vs 混双），分别标注避免误导
        Map<Long, Integer> genders = gendersOf(gameId);
        // 计分日志：每场的裁判（首次计分人）与改分历史
        Map<Long, List<GameMatchesResponse.ScoreLogItem>> scoreLogsByMatch = loadScoreLogs(list);
        List<GameMatchesResponse.MatchItem> items = list.stream()
                .map(m -> {
                    List<Long> teamA = split(m.getTeamA());
                    List<Long> teamB = split(m.getTeamB());
                    List<GameMatchesResponse.ScoreLogItem> scoreLogs =
                            scoreLogsByMatch.getOrDefault(m.getId(), List.of());
                    GameMatchesResponse.ScoreLogItem referee = scoreLogs.stream()
                            .filter(l -> Objects.equals(l.getAction(), Constants.SCORE_ACTION_FIRST))
                            .findFirst()
                            .orElse(null);
                    return GameMatchesResponse.MatchItem.builder()
                            .matchId(m.getId())
                            .format(m.getFormat())
                            .teamAFormatName(MatchFormatUtil.sideFormatName(teamA, genders))
                            .teamBFormatName(MatchFormatUtil.sideFormatName(teamB, genders))
                            .teamA(teamA)
                            .teamB(teamB)
                            .teamANames(teamA.stream().map(id -> name(displayNames, id)).toList())
                            .teamBNames(teamB.stream().map(id -> name(displayNames, id)).toList())
                            .scoreA(m.getScoreA())
                            .scoreB(m.getScoreB())
                            .winner(m.getWinner())
                            .settled(Objects.equals(m.getSettleStatus(), Constants.SETTLE_DONE))
                            .refereeId(referee != null ? referee.getOperatorId() : null)
                            .refereeName(referee != null ? referee.getOperatorName() : null)
                            .scoreLogs(scoreLogs)
                            .build();
                })
                .toList();
        int finished = (int) items.stream()
                .filter(i -> i.getWinner() != null && i.getWinner() != 0)
                .count();
        return GameMatchesResponse.builder()
                .gameId(gameId)
                .status(game.getStatus())
                .schemeId(game.getSchemeId())
                .schemeName(ArrangeEngine.schemeNameOf(game.getSchemeId()))
                .finishedCount(finished)
                .totalCount(items.size())
                .matches(items)
                .build();
    }

    /**
     * 批量加载每场对阵的计分日志（按提交顺序升序）。
     * 日志操作人未必在报名名单里（如发起人代录），故单独取用户表并用昵称优先的展示名。
     */
    private Map<Long, List<GameMatchesResponse.ScoreLogItem>> loadScoreLogs(List<MatchGame> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        List<Long> matchIds = list.stream().map(MatchGame::getId).toList();
        List<MatchScoreLog> logs = matchScoreLogMapper.selectList(
                new LambdaQueryWrapper<MatchScoreLog>()
                        .in(MatchScoreLog::getMatchId, matchIds)
                        .orderByAsc(MatchScoreLog::getId));
        if (logs.isEmpty()) {
            return Map.of();
        }
        // 操作人展示名：昵称优先，回退姓名，再回退球友+id
        Map<Long, String> operatorLabels = new HashMap<>();
        Map<Long, String> operatorAvatars = new HashMap<>();
        for (Long opId : logs.stream().map(MatchScoreLog::getOperatorId).distinct().toList()) {
            User u = userMapper.selectById(opId);
            String label = null;
            if (u != null) {
                if (u.getNickname() != null && !u.getNickname().isBlank()) {
                    label = u.getNickname().trim();
                } else if (u.getName() != null && !u.getName().isBlank()) {
                    label = u.getName().trim();
                }
                operatorAvatars.put(opId, u.getAvatar());
            }
            operatorLabels.put(opId, label != null ? label : "球友" + opId);
        }
        Map<Long, List<GameMatchesResponse.ScoreLogItem>> out = new HashMap<>();
        for (MatchScoreLog l : logs) {
            out.computeIfAbsent(l.getMatchId(), k -> new java.util.ArrayList<>())
                    .add(GameMatchesResponse.ScoreLogItem.builder()
                            .operatorId(l.getOperatorId())
                            .operatorName(operatorLabels.getOrDefault(l.getOperatorId(), "球友" + l.getOperatorId()))
                            .operatorAvatar(operatorAvatars.get(l.getOperatorId()))
                            .action(l.getAction())
                            .scoreA(l.getScoreA())
                            .scoreB(l.getScoreB())
                            .prevScoreA(l.getPrevScoreA())
                            .prevScoreB(l.getPrevScoreB())
                            .time(l.getCreateTime())
                            .build());
        }
        return out;
    }

    /**
     * 轮排对阵里 userId 对应的展示名。
     * 轮排使用真实信息：登录用户一律看真实姓名（与是否匿名报名无关）；未登录访客只看匿名昵称。
     */
    private Map<Long, String> displayNamesOf(Long gameId) {
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));
        Map<Long, User> userMap = loadUserMap(regs);
        Map<Long, String> realNames = loadRealNames(regs, userMap);
        Map<Long, String> out = new HashMap<>();
        for (Registration r : regs) {
            String realName = realNames.get(r.getUserId());
            out.put(r.getUserId(), realName != null ? realName : "球友" + r.getUserId());
        }
        return out;
    }

    /**
     * 轮排对阵里 userId 对应的当前性别。
     * 用于判定两队各自的构成（男双 / 女双 / 混双）。
     *
     * <p>口径与编排保持一致：user 表最新值优先、报名快照兜底，
     * 否则会出现「编排时按男队员排的，展示时却标成女队员」的错位。
     */
    private Map<Long, Integer> gendersOf(Long gameId) {
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, gameId));
        return snapshotService.genderMap(regs, loadUserMap(regs));
    }

    private static String name(Map<Long, String> names, Long userId) {
        return names.getOrDefault(userId, "球友#" + userId);
    }

    private static List<Long> split(String ids) {
        if (ids == null || ids.isBlank()) {
            return List.of();
        }
        return Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    private Game requireGame(Long gameId) {
        Game game = gameMapper.selectById(gameId);
        if (game == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "球局不存在");
        }
        return game;
    }

    /**
     * 标题唯一校验（业务层主动报错，前端友好提示）。
     *
     * @param excludeId 排除的球局 id（编辑场景传自己，新增场景传 null）
     */
    private void checkTitleUnique(Long excludeId, String title) {
        if (title == null || title.isBlank()) return;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Game> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.eq(Game::getTitle, title.trim());
        if (excludeId != null) {
            w.ne(Game::getId, excludeId);
        }
        Long cnt = gameMapper.selectCount(w);
        if (cnt != null && cnt > 0) {
            throw new BizException(ErrorCode.GAME_TITLE_DUPLICATE);
        }
    }

    /**
     * 前端标题查重接口：公开（发布前预先校验用），返回 { available: boolean }
     */
    public boolean titleAvailable(String title, Long excludeId) {
        if (title == null || title.isBlank()) return true;
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Game> w =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        w.eq(Game::getTitle, title.trim());
        if (excludeId != null) {
            w.ne(Game::getId, excludeId);
        }
        Long cnt = gameMapper.selectCount(w);
        return cnt == null || cnt == 0;
    }

    private GameResponse buildGameResponse(Game game) {
        List<Registration> regs = registrationMapper.selectList(
                new LambdaQueryWrapper<Registration>().eq(Registration::getGameId, game.getId()));
        Map<Long, User> userMap = loadUserMap(regs);
        Map<Long, String> realNames = loadRealNames(regs, userMap);

        // 单独查发起人（发起人可能未在 registrations 里）
        String creatorName = null;
        String creatorAvatar = null;
        Integer creatorGender = null;
        Integer creatorRating = null;
        if (game.getCreatorId() != null) {
            User cu = userMapper.selectById(game.getCreatorId());
            if (cu != null) {
                creatorName = cu.getName();
                creatorAvatar = cu.getAvatar();
                creatorGender = cu.getGender();
                creatorRating = cu.getRating();
            }
        }

        // 单独查关联球场（court 表，取 lat/lng 供前端地图展示）
        // 优先 game.court_id 关联；兜底：court_id 为空时按 location 模糊匹配 court.name
        String courtName = null;
        java.math.BigDecimal courtLat = null;
        java.math.BigDecimal courtLng = null;
        Long courtIdOut = game.getCourtId();

        com.yuyue.entity.Court ct = null;
        if (game.getCourtId() != null) {
            ct = courtMapper.selectById(game.getCourtId());
        } else if (game.getLocation() != null && !game.getLocation().isBlank()) {
            // 兜底：按 location 文本模糊匹配 court.name（历史球局 court_id 可能为 NULL）
            ct = courtMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.yuyue.entity.Court>()
                    .like(com.yuyue.entity.Court::getName, game.getLocation().trim())
                    .last("LIMIT 1"));
            if (ct != null) {
                courtIdOut = ct.getId();
            }
        }
        if (ct != null) {
            courtName = ct.getName();
            courtLat = ct.getLat();
            courtLng = ct.getLng();
        }

        return GameResponse.builder()
                .id(game.getId())
                .title(game.getTitle())
                .mode(game.getMode())
                .location(game.getLocation())
                .courtId(courtIdOut)
                .courtName(courtName)
                .courtLat(courtLat)
                .courtLng(courtLng)
                .remark(game.getRemark())
                .playDate(game.getPlayDate())
                .startTime(game.getStartTime())
                .endTime(game.getEndTime())
                .maxPlayers(game.getMaxPlayers())
                .courtCount(game.getCourtCount())
                .schemeId(game.getSchemeId())
                .status(game.getStatus())
                .creatorId(game.getCreatorId())
                .hidden(game.getHidden() == null ? 0 : game.getHidden())
                .isVerified(game.getIsVerified() == null ? 0 : game.getIsVerified())
                .creatorName(creatorName)
                .creatorAvatar(creatorAvatar)
                .creatorGender(creatorGender)
                .creatorRating(creatorRating)
                .cover(game.getCover())
                .registeredCount(regs.size())
                .registrations(regs.stream()
                        .map(r -> {
                            User u = userMap.get(r.getUserId());
                            String name = u != null && u.getName() != null ? u.getName() : "球友" + r.getUserId();
                            return GameResponse.RegistrationItem.builder()
                                    .userId(r.getUserId())
                                    .displayName(name)
                                    .avatar(u != null ? u.getAvatar() : null)
                                    // 性别与积分一律用 user 表最新值，不用报名快照：
                                    // 否则球友改过性别后，这里显示的和轮排编排实际依据的会对不上
                                    .gender(snapshotService.genderOf(r, u))
                                    .rating(snapshotService.ratingOf(r, u))
                                    .build();
                        })
                        .toList())
                .build();
    }

    /** 批量取报名用户完整信息，避免多次查询用户表 */
    private Map<Long, User> loadUserMap(List<Registration> regs) {
        if (regs.isEmpty()) {
            return Map.of();
        }
        List<Long> userIds = regs.stream().map(Registration::getUserId).distinct().toList();
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
    }

    /** 批量取报名人真实姓名 */
    private Map<Long, String> loadRealNames(List<Registration> regs, Map<Long, User> userMap) {
        if (regs.isEmpty()) {
            return Map.of();
        }
        return userMap.values().stream()
                .filter(u -> u.getName() != null && !u.getName().isBlank())
                .collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
    }
}
