package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.dto.TournamentBracketResponse;
import com.yuyue.dto.TournamentCreateRequest;
import com.yuyue.dto.TournamentDetailResponse;
import com.yuyue.dto.TournamentEntryRequest;
import com.yuyue.dto.TournamentListItem;
import com.yuyue.dto.TournamentMatchItem;
import com.yuyue.dto.TournamentMatchesResponse;
import com.yuyue.dto.TournamentScoreRequest;
import com.yuyue.engine.TournamentEngine;
import com.yuyue.entity.Court;
import com.yuyue.entity.Tournament;
import com.yuyue.entity.TournamentEntry;
import com.yuyue.entity.TournamentGroup;
import com.yuyue.entity.TournamentMatch;
import com.yuyue.entity.User;
import com.yuyue.exception.BizException;
import com.yuyue.mapper.CourtMapper;
import com.yuyue.mapper.TournamentEntryMapper;
import com.yuyue.mapper.TournamentGroupMapper;
import com.yuyue.mapper.TournamentMatchMapper;
import com.yuyue.mapper.TournamentMapper;
import com.yuyue.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 大型比赛（锦标赛）业务
 *
 * <p>与「球局」的定位差异：球局是ELO 驱动的「找势均力敌的人一起打」，
 * 这里是赛制驱动的「同一场比赛决出冠军」——所以本服务<b>全程不碰积分</b>，
 * 名次只落在 tournament_entry.final_rank 与赛事表的领奖台字段上。
 *
 * <p>赛制状态机（单个组别独立跑）：
 * <pre>
 *   报名中 ──draw()──> 小组赛（stage=1，组内单循环）
 *                          │ 该组别所有小组完赛
 *                          ▼
 *                      淘汰赛首轮（stage=2，标准种子 1vsN 配对，同组必然分开）
 *                          │ 本轮全出结果
 *                          ▼
 *                      …… 32 → 16 → 8 → 4（四强）
 *                          │ 四强全出结果
 *                          ▼
 *                      季军赛（stage=3）+ 决赛（stage=4）→ 名次定，赛事结束
 * </pre>
 *
 * <p>撤销策略：提交 winner=0 即撤销本场，服务会连带清空<b>所有后续轮次</b>的对阵，
 * 并把参赛单位状态整体回退到「已抽签」，重新让裁判从头打一遍——
 * 这样任何时刻的赛程都是自洽的，不会出现「A 赢了但 B 已经赢了下一轮」这种脏数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TournamentService {

    private final TournamentMapper tournamentMapper;
    private final TournamentEntryMapper entryMapper;
    private final TournamentGroupMapper groupMapper;
    private final TournamentMatchMapper matchMapper;
    private final UserMapper userMapper;
    private final CourtMapper courtMapper;
    private final TournamentEngine engine;

    /* ==================== 文案映射 ==================== */

    private static final Map<Integer, String> FORMAT_NAMES = Map.of(
            Constants.FORMAT_SINGLES, "单打",
            Constants.FORMAT_MEN_DOUBLES, "男双",
            Constants.FORMAT_WOMEN_DOUBLES, "女双",
            Constants.FORMAT_MIXED_DOUBLES, "混双");

    private static final Map<Integer, String> TOURNAMENT_STATUS_NAMES = Map.of(
            Constants.TOURNAMENT_STATUS_OPEN, "报名中",
            Constants.TOURNAMENT_STATUS_DRAWN, "已抽签",
            Constants.TOURNAMENT_STATUS_RUNNING, "进行中",
            Constants.TOURNAMENT_STATUS_FINISHED, "已结束",
            Constants.TOURNAMENT_STATUS_CANCELLED, "已取消");

    private static final Map<Integer, String> ENTRY_STATUS_NAMES = Map.of(
            Constants.TOURNAMENT_ENTRY_REGISTERED, "待抽签",
            Constants.TOURNAMENT_ENTRY_DRAWN, "小组赛",
            Constants.TOURNAMENT_ENTRY_ALIVE, "仍在赛",
            Constants.TOURNAMENT_ENTRY_OUT, "已淘汰");

    private static final Map<Integer, String> STAGE_NAMES = Map.of(
            Constants.TOURNAMENT_STAGE_GROUP, "小组赛",
            Constants.TOURNAMENT_STAGE_KNOCKOUT, "淘汰赛",
            Constants.TOURNAMENT_STAGE_BRONZE, "季军赛",
            Constants.TOURNAMENT_STAGE_FINAL, "决赛");

    /** 淘汰赛规模 → 轮次名（前端展示「十六强」这类中文标签） */
    private static final Map<Integer, String> KNOCKOUT_ROUND_NAMES = Map.of(
            4, "四强",
            8, "八强",
            16, "十六强",
            32, "三十二强",
            64, "六十四强",
            128, "一百二十八强",
            256, "二百五十六强");

    private static final Map<Integer, String> RANK_NAMES = Map.of(
            1, "冠军",
            2, "亚军",
            3, "季军",
            4, "第 4 名");

    /* ==================== 查询 ==================== */

    /** 赛事广场：报名中的排前面，其余按创建时间倒序 */
    public List<TournamentListItem> list(Long userId) {
        List<Tournament> list = tournamentMapper.selectList(new LambdaQueryWrapper<Tournament>()
                .ne(Tournament::getStatus, Constants.TOURNAMENT_STATUS_CANCELLED)
                .orderByAsc(Tournament::getStatus)
                .orderByDesc(Tournament::getId));
        if (list.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> ids = list.stream().map(Tournament::getId).toList();
        // 一次把所有参赛单位捞出来分组统计，避免列表页 N+1 查询
        Map<Long, List<TournamentEntry>> entryByTournament = entryMapper.selectList(
                        new LambdaQueryWrapper<TournamentEntry>().in(TournamentEntry::getTournamentId, ids))
                .stream()
                .collect(Collectors.groupingBy(TournamentEntry::getTournamentId));

        Map<Long, TournamentEntry> championByTournament = championEntries(ids);
        Set<Long> myTournamentIds = new LinkedHashSet<>(myTournamentIds(ids, userId));

        List<TournamentListItem> out = new ArrayList<>(list.size());
        for (Tournament t : list) {
            List<TournamentEntry> entries = entryByTournament.getOrDefault(t.getId(), List.of());
            int maxEntries = nz(t.getMaxEntries());
            List<Integer> formats = parseFormats(t.getFormatList());
            TournamentEntry champion = championByTournament.get(t.getId());

            out.add(TournamentListItem.builder()
                    .id(t.getId())
                    .title(t.getTitle())
                    .location(t.getLocation())
                    .playDate(t.getPlayDate())
                    .status(t.getStatus())
                    .statusText(statusText(t.getStatus()))
                    .formats(formats)
                    .formatNames(formats.stream().map(this::formatName).toList())
                    .entryCount(entries.size())
                    .maxEntries(maxEntries)
                    .maxPeople(peopleUpperBound(formats, maxEntries))
                    .joined(myTournamentIds.contains(t.getId()))
                    .championName(champion == null ? null : teamName(champion, loadUsers(entries)))
                    .build());
        }
        return out;
    }

    /** 赛事详情：基本信息 + 各组别报名情况 + 我的参赛单位 + 领奖台 */
    public TournamentDetailResponse detail(Long id, Long userId) {
        Tournament t = requireTournament(id);
        List<TournamentEntry> entries = loadEntries(id);
        Map<Long, User> users = loadUsers(entries);
        boolean admin = isAdmin(userId);
        boolean drawn = isDrawn(t);
        List<Integer> formats = parseFormats(t.getFormatList());
        int maxEntries = nz(t.getMaxEntries());

        // 我的参赛单位：既可能我当队长，也可能我是别人的搭档
        List<TournamentDetailResponse.MyEntryItem> mine = new ArrayList<>();
        for (TournamentEntry e : entries) {
            boolean involved = userId != null
                    && (Objects.equals(e.getUserId(), userId) || Objects.equals(e.getPartnerId(), userId));
            if (involved) {
                mine.add(toMyEntry(e, users));
            }
        }

        List<TournamentDetailResponse.FormatStat> stats = new ArrayList<>();
        Map<Integer, List<TournamentGroup>> groupsByFormat = drawn
                ? loadGroups(id).stream().collect(Collectors.groupingBy(TournamentGroup::getFormat))
                : Map.of();
        for (int format : formats) {
            List<TournamentEntry> inFormat = entries.stream()
                    .filter(e -> Objects.equals(e.getFormat(), format))
                    .toList();
            stats.add(TournamentDetailResponse.FormatStat.builder()
                    .format(format)
                    .formatName(formatName(format))
                    .teamSize(teamSize(format))
                    .entryCount(inFormat.size())
                    .maxEntries(maxEntries)
                    .maxPeople(maxEntries * teamSize(format))
                    .full(inFormat.size() >= maxEntries)
                    .joined(inFormat.stream().anyMatch(e -> userId != null
                            && (Objects.equals(e.getUserId(), userId)
                            || Objects.equals(e.getPartnerId(), userId))))
                    .groupCount(groupsByFormat.getOrDefault(format, List.of()).size())
                    .build());
        }

        return TournamentDetailResponse.builder()
                .id(t.getId())
                .title(t.getTitle())
                .location(t.getLocation())
                .locationName(resolveLocationName(t))
                .playDate(t.getPlayDate())
                .remark(t.getRemark())
                .status(t.getStatus())
                .statusText(statusText(t.getStatus()))
                .groupSize(t.getGroupSize())
                .advancePerGroup(t.getAdvancePerGroup())
                .maxEntries(maxEntries)
                .drawn(drawn)
                .admin(admin)
                .openForEntry(Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_OPEN))
                .formats(stats)
                .myEntries(mine)
                .podium(podium(t, entries, users))
                .build();
    }

    /** 对阵图：每个组别的小组赛积分榜 + 淘汰赛逐轮 + 最终名次 */
    public TournamentBracketResponse bracket(Long id, Long userId) {
        Tournament t = requireTournament(id);
        List<Integer> formats = parseFormats(t.getFormatList());
        List<TournamentEntry> allEntries = loadEntries(id);
        Map<Long, TournamentEntry> entryMap = allEntries.stream()
                .collect(Collectors.toMap(TournamentEntry::getId, e -> e, (a, b) -> a, LinkedHashMap::new));
        Map<Long, User> users = loadUsers(allEntries);
        List<TournamentGroup> groups = isDrawn(t) ? loadGroups(id) : new ArrayList<>();
        List<TournamentMatch> matches = loadMatches(id);

        List<TournamentBracketResponse.FormatBoard> boards = new ArrayList<>();
        for (int format : formats) {
            List<TournamentEntry> inFormat = allEntries.stream()
                    .filter(e -> Objects.equals(e.getFormat(), format))
                    .toList();

            // 小组赛：积分榜 + 组内对阵
            List<TournamentBracketResponse.GroupBoard> groupBoards = new ArrayList<>();
            for (TournamentGroup g : groups.stream()
                    .filter(g -> Objects.equals(g.getFormat(), format))
                    .sorted(Comparator.comparing(TournamentGroup::getSeq))
                    .toList()) {
                List<TournamentMatch> groupMatches = matches.stream()
                        .filter(m -> Objects.equals(m.getStage(), Constants.TOURNAMENT_STAGE_GROUP))
                        .filter(m -> Objects.equals(m.getGroupId(), g.getId()))
                        .sorted(Comparator.comparing(TournamentMatch::getRoundNo)
                                .thenComparing(TournamentMatch::getMatchNo))
                        .toList();

                List<TournamentBracketResponse.StandingItem> standings = inFormat.stream()
                        .filter(e -> Objects.equals(e.getGroupId(), g.getId()))
                        .sorted(Comparator.comparing(TournamentEntry::getGroupRank,
                                        Comparator.nullsLast(Comparator.naturalOrder()))
                                .thenComparing(TournamentEntry::getId))
                        .map(e -> toStanding(e, users))
                        .toList();

                groupBoards.add(TournamentBracketResponse.GroupBoard.builder()
                        .groupId(g.getId())
                        .groupNo(g.getGroupNo())
                        .entryCount(nz(g.getEntryCount()))
                        .status(g.getStatus())
                        .standings(standings)
                        .matches(groupMatches.stream().map(m -> toMatchItem(m, entryMap, users, userId)).toList())
                        .build());
            }

            // 淘汰赛：stage=2 的各轮 + 季军赛 + 决赛
            List<TournamentBracketResponse.RoundBoard> rounds = new ArrayList<>();
            rounds.addAll(knockoutRounds(matches, entryMap, users, userId,
                    Constants.TOURNAMENT_STAGE_KNOCKOUT));
            rounds.addAll(knockoutRounds(matches, entryMap, users, userId,
                    Constants.TOURNAMENT_STAGE_BRONZE));
            rounds.addAll(knockoutRounds(matches, entryMap, users, userId,
                    Constants.TOURNAMENT_STAGE_FINAL));

            // 名次榜：已淘汰的按名次升序；仍在赛的单列
            List<TournamentBracketResponse.RankItem> ranking = inFormat.stream()
                    .filter(e -> e.getFinalRank() != null)
                    .sorted(Comparator.comparing(TournamentEntry::getFinalRank)
                            .thenComparing(TournamentEntry::getId))
                    .map(e -> toRankItem(e, users))
                    .toList();
            List<TournamentMatchItem.TeamItem> alive = inFormat.stream()
                    .filter(e -> e.getFinalRank() == null && !Objects.equals(e.getStatus(),
                            Constants.TOURNAMENT_ENTRY_REGISTERED))
                    .map(e -> toTeamItem(e, users))
                    .toList();

            boards.add(TournamentBracketResponse.FormatBoard.builder()
                    .format(format)
                    .formatName(formatName(format))
                    .groups(groupBoards)
                    .knockouts(rounds)
                    .ranking(ranking)
                    .alive(alive)
                    .build());
        }

        return TournamentBracketResponse.builder()
                .id(t.getId())
                .title(t.getTitle())
                .status(t.getStatus())
                .statusText(statusText(t.getStatus()))
                .formats(boards)
                .build();
    }

    /** 赛程录入：按组别把对阵拆成「待录入 / 已出结果」两栏 */
    public TournamentMatchesResponse matches(Long id, Long userId) {
        Tournament t = requireTournament(id);
        List<Integer> formats = parseFormats(t.getFormatList());
        List<TournamentEntry> allEntries = loadEntries(id);
        Map<Long, TournamentEntry> entryMap = allEntries.stream()
                .collect(Collectors.toMap(TournamentEntry::getId, e -> e, (a, b) -> a, LinkedHashMap::new));
        Map<Long, User> users = loadUsers(allEntries);
        List<TournamentMatch> allMatches = loadMatches(id);

        List<TournamentMatchesResponse.FormatMatches> out = new ArrayList<>();
        for (int format : formats) {
            List<TournamentMatchItem> items = allMatches.stream()
                    .filter(m -> Objects.equals(m.getFormat(), format))
                    .sorted(matchOrder())
                    .map(m -> toMatchItem(m, entryMap, users, userId))
                    .toList();
            out.add(TournamentMatchesResponse.FormatMatches.builder()
                    .format(format)
                    .formatName(formatName(format))
                    // 待录入在前（正序：要按赛程从头打），已完成的沉底（倒序：刚打完的最上面）
                    .pending(items.stream().filter(i -> !i.isFinished()).toList())
                    .done(items.stream().filter(TournamentMatchItem::isFinished)
                            .sorted(Comparator.comparing(TournamentMatchItem::getId).reversed())
                            .toList())
                    .build());
        }

        return TournamentMatchesResponse.builder()
                .id(t.getId())
                .title(t.getTitle())
                .status(t.getStatus())
                .statusText(statusText(t.getStatus()))
                .admin(isAdmin(userId))
                .formats(out)
                .build();
    }

    /** 对阵排序：阶段升序（小组赛 → 淘汰赛 → 季军赛 → 决赛），同阶段轮次升序、场次升序 */
    private Comparator<TournamentMatch> matchOrder() {
        return Comparator.comparing(TournamentMatch::getStage)
                .thenComparing(TournamentMatch::getRoundNo)
                .thenComparing(TournamentMatch::getMatchNo);
    }

    /* ==================== 赛事管理 ==================== */

    /** 创建赛事（管理员） */
    @Transactional
    public Long create(TournamentCreateRequest req, Long creatorId) {
        requireAdmin(creatorId);

        List<Integer> formats = normalizeFormats(req.getFormats());
        int groupSize = req.getGroupSize() == null ? 4 : req.getGroupSize();
        int advance = req.getAdvancePerGroup() == null ? 1 : req.getAdvancePerGroup();
        if (advance >= groupSize) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "每组晋级名额必须小于每组 " + groupSize + " 队，否则小组赛没有意义");
        }

        Tournament t = new Tournament();
        t.setTitle(req.getTitle().trim());
        t.setLocation(req.getLocation() == null ? null : req.getLocation().trim());
        t.setCourtId(req.getCourtId());
        t.setPlayDate(req.getPlayDate());
        t.setRemark(req.getRemark() == null ? null : req.getRemark().trim());
        t.setFormatList(formats.stream().map(String::valueOf).collect(Collectors.joining(",")));
        t.setGroupSize(groupSize);
        t.setAdvancePerGroup(advance);
        t.setMaxEntries(req.getMaxEntries() == null ? 64 : req.getMaxEntries());
        t.setStatus(Constants.TOURNAMENT_STATUS_OPEN);
        t.setCreatorId(creatorId);
        tournamentMapper.insert(t);

        log.info("创建赛事: id={}, title={}, 组别={}, 每组{}队×{}名额",
                t.getId(), t.getTitle(), t.getFormatList(), groupSize, advance);
        return t.getId();
    }

    /** 取消赛事（管理员 / 创建人），只能取消还没开打的 */
    @Transactional
    public void cancel(Long id, Long operatorId) {
        Tournament t = requireTournament(id);
        requireOwnerOrAdmin(t, operatorId);
        int status = nz(t.getStatus());
        if (status != Constants.TOURNAMENT_STATUS_OPEN && status != Constants.TOURNAMENT_STATUS_DRAWN) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "赛事已开打，不能再取消");
        }
        t.setStatus(Constants.TOURNAMENT_STATUS_CANCELLED);
        tournamentMapper.updateById(t);
        log.info("取消赛事: id={}", id);
    }

    /* ==================== 报名 ==================== */

    /**
     * 报名：写入一个参赛单位。
     * 双打由队长（本登录用户）代搭档一起提交，所以 partnerId 必填；
     * 搭档的性别是否满足组别要求在这里校验（查 user.gender）。
     */
    @Transactional
    public Long entry(Long tournamentId, TournamentEntryRequest req, Long userId) {
        Tournament t = requireTournament(tournamentId);
        if (!Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_OPEN)) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR,
                    isDrawn(t) ? "已抽签，不能再报名" : "赛事当前不接受报名");
        }
        int format = nz(req.getFormat());
        if (!parseFormats(t.getFormatList()).contains(format)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "本赛事未开设「" + formatName(format) + "」组别");
        }

        boolean doubles = format != Constants.FORMAT_SINGLES;
        Long requestedPartner = req.getPartnerId();
        if (!doubles) {
            requestedPartner = null;
        } else {
            if (requestedPartner == null) {
                throw new BizException(ErrorCode.PARAM_ERROR, formatName(format) + "必须指定搭档");
            }
            if (Objects.equals(requestedPartner, userId)) {
                throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID, "搭档不能是自己");
            }
        }
        final Long partnerId = requestedPartner;

        User captain = userMapper.selectById(userId);
        if (captain == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        User partner = null;
        if (doubles) {
            partner = userMapper.selectById(partnerId);
            if (partner == null) {
                throw new BizException(ErrorCode.USER_NOT_FOUND, "搭档不存在");
            }
            checkGenderRule(format, captain, partner);
        }

        // 同一个人在本赛事只能出现一次（无论当队长还是当搭档）
        boolean duplicated = loadEntries(tournamentId).stream().anyMatch(e ->
                Objects.equals(e.getUserId(), userId)
                        || Objects.equals(e.getPartnerId(), userId)
                        || (partnerId != null && (Objects.equals(e.getUserId(), partnerId)
                        || Objects.equals(e.getPartnerId(), partnerId))));
        if (duplicated) {
            throw new BizException(ErrorCode.TOURNAMENT_ENTRY_EXISTS);
        }

        int maxEntries = nz(t.getMaxEntries());
        long inFormat = entryMapper.selectCount(new LambdaQueryWrapper<TournamentEntry>()
                .eq(TournamentEntry::getTournamentId, tournamentId)
                .eq(TournamentEntry::getFormat, format));
        if (inFormat >= maxEntries) {
            throw new BizException(ErrorCode.TOURNAMENT_FULL);
        }

        TournamentEntry e = new TournamentEntry();
        e.setTournamentId(tournamentId);
        e.setFormat(format);
        e.setUserId(userId);
        e.setPartnerId(partnerId);
        e.setContact(req.getContact() == null ? null : req.getContact().trim());
        e.setGroupWin(0);
        e.setGroupLoss(0);
        e.setGroupPointDiff(0);
        e.setAdvance(0);
        e.setStatus(Constants.TOURNAMENT_ENTRY_REGISTERED);
        entryMapper.insert(e);

        log.info("赛事报名: tournament={}, {} 参赛单位={} ({} / {})", tournamentId,
                formatName(format), e.getId(), captain.getName(),
                partner == null ? "-" : partner.getName());
        return e.getId();
    }

    /** 撤回报名：只能在报名中（未抽签）时撤回 */
    @Transactional
    public void cancelEntry(Long tournamentId, Long entryId, Long userId) {
        Tournament t = requireTournament(tournamentId);
        TournamentEntry e = entryMapper.selectById(entryId);
        if (e == null || !Objects.equals(e.getTournamentId(), tournamentId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "报名记录不存在");
        }
        // 未抽签：队长可以自行撤回；已抽签：只有管理员 / 创建人能移出参赛名单
        if (Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_OPEN)) {
            if (!Objects.equals(e.getUserId(), userId)) {
                throw new BizException(ErrorCode.FORBIDDEN, "只能撤回自己提交的报名");
            }
        } else {
            requireOwnerOrAdmin(t, userId);
        }
        entryMapper.deleteById(entryId);
        log.info("撤回报名: tournament={}, entry={}", tournamentId, entryId);
    }

    /* ==================== 抽签分组 ==================== */

    /**
     * 抽签：随机打散分组 + 生成小组赛组内单循环赛程。
     *
     * <p>各组别独立处理；任一组别凑不出合法的淘汰赛规模（晋级数不是 2 的幂 / 少于 4 队），
     * 整场抽签都会失败并给出具体是哪个组别、当前晋级多少人——宁可让管理员改配置重来，
     * 也不要生成一份打不下去的签表。
     */
    @Transactional
    public Map<String, Object> draw(Long tournamentId, Long operatorId) {
        Tournament t = requireTournament(tournamentId);
        requireOwnerOrAdmin(t, operatorId);
        if (!Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_OPEN)) {
            throw new BizException(ErrorCode.TOURNAMENT_ALREADY_DRAWN, "已抽签，不能重复抽签");
        }

        int groupSize = nz(t.getGroupSize());
        int advancePerGroup = nz(t.getAdvancePerGroup());
        List<Integer> formats = parseFormats(t.getFormatList());
        List<TournamentEntry> allEntries = loadEntries(tournamentId);

        // 先整体校验，任何一个组别不合法就整场不抽
        for (int format : formats) {
            List<TournamentEntry> inFormat = entriesOf(allEntries, format);
            if (inFormat.size() < 2) {
                // 不足 2 队的组别不参与抽签（下面真正的抽签循环里也会跳过）
                continue;
            }
            int groupCount = engine.groupCount(inFormat.size(), groupSize);
            int advanceTotal = groupCount * advancePerGroup;
            try {
                engine.knockoutSize(advanceTotal);
            } catch (BizException e) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        formatName(format) + "组共 " + inFormat.size() + " 队，按每组 " + groupSize
                                + " 队 × 每组 " + advancePerGroup + " 名 = 晋级 " + advanceTotal + " 人。"
                                + e.getMessage());
            }
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        List<Map<String, Object>> formatSummary = new ArrayList<>();

        for (int format : formats) {
            List<TournamentEntry> inFormat = entriesOf(allEntries, format);
            if (inFormat.size() < 2) {
                log.warn("赛事 {} 的 {} 组只有 {} 队，不足 2 队，跳过抽签", tournamentId, formatName(format),
                        inFormat.size());
                continue;
            }

            // 1) 随机抽签分组
            List<List<Long>> drawn = engine.drawGroups(
                    inFormat.stream().map(TournamentEntry::getId).toList(), groupSize);

            // 2) 落库小组，回填参赛单位的小组信息
            for (int i = 0; i < drawn.size(); i++) {
                TournamentGroup g = new TournamentGroup();
                g.setTournamentId(tournamentId);
                g.setFormat(format);
                g.setGroupNo(engine.groupNo(i + 1));
                g.setSeq(i + 1);
                g.setEntryCount(drawn.get(i).size());
                g.setStatus(Constants.TOURNAMENT_GROUP_PENDING);
                groupMapper.insert(g);

                for (int j = 0; j < drawn.get(i).size(); j++) {
                    Long entryId = drawn.get(i).get(j);
                    TournamentEntry e = getEntry(entryId);
                    e.setGroupId(g.getId());
                    e.setGroupNo(g.getGroupNo());
                    e.setSeedNo(j + 1);
                    e.setGroupWin(0);
                    e.setGroupLoss(0);
                    e.setGroupPointDiff(0);
                    e.setGroupRank(null);
                    e.setAdvance(0);
                    e.setStatus(Constants.TOURNAMENT_ENTRY_DRAWN);
                    entryMapper.updateById(e);
                }
            }

            // 3) 组内单循环赛程
            for (int i = 0; i < drawn.size(); i++) {
                TournamentGroup g = findGroup(tournamentId, format, engine.groupNo(i + 1));
                List<List<TournamentEngine.Pair>> schedule = engine.roundRobin(drawn.get(i));
                for (int r = 0; r < schedule.size(); r++) {
                    List<TournamentEngine.Pair> pairs = schedule.get(r);
                    for (int k = 0; k < pairs.size(); k++) {
                        TournamentMatch m = newMatch(tournamentId, format, Constants.TOURNAMENT_STAGE_GROUP);
                        m.setGroupId(g.getId());
                        m.setRoundNo(r + 1);
                        m.setRoundSize(drawn.get(i).size());
                        m.setMatchNo(k + 1);
                        m.setCourt(k + 1);
                        m.setTeamA(pairs.get(k).a());
                        m.setTeamB(pairs.get(k).b());
                        matchMapper.insert(m);
                    }
                }
            }

            Map<String, Object> one = new LinkedHashMap<>();
            one.put("format", format);
            one.put("formatName", formatName(format));
            one.put("entryCount", inFormat.size());
            one.put("groupCount", drawn.size());
            one.put("knockoutSize", drawn.size() * advancePerGroup);
            formatSummary.add(one);
        }

        if (formatSummary.isEmpty()) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "没有任何组别凑够 2 队，无法抽签");
        }

        t.setStatus(Constants.TOURNAMENT_STATUS_DRAWN);
        tournamentMapper.updateById(t);

        summary.put("tournamentId", tournamentId);
        summary.put("formats", formatSummary);
        log.info("赛事抽签完成: tournament={}, 明细={}", tournamentId, formatSummary);
        return summary;
    }

    /* ==================== 计分与赛程推进 ==================== */

    /**
     * 赛事计分：只认 winner（1A 2B），比分可选填、仅作留档。
     * winner=0 表示撤销本场（误点救回），会连带清空后续轮次并把参赛单位状态回退。
     *
     * @return 本场结果与是否已推进到下一阶段，前端用来提示「A 组已完赛，淘汰赛签表已生成」
     */
    @Transactional
    public Map<String, Object> score(Long matchId, TournamentScoreRequest req, Long operatorId) {
        TournamentMatch m = matchMapper.selectById(matchId);
        if (m == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "对阵不存在");
        }
        Tournament t = requireTournament(m.getTournamentId());
        if (Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_CANCELLED)) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "赛事已取消");
        }
        requireScorePermission(t, m, operatorId);

        int winner = nz(req.getWinner());
        if (winner == Constants.WINNER_NONE) {
            return revoke(t, m, operatorId);
        }
        if (winner != Constants.WINNER_A && winner != Constants.WINNER_B) {
            throw new BizException(ErrorCode.PARAM_ERROR, "胜方只能是1（A 队）或 2（B 队）");
        }
        Integer scoreA = req.getScoreA();
        Integer scoreB = req.getScoreB();
        if (scoreA != null && scoreB != null && scoreA.equals(scoreB)) {
            throw new BizException(ErrorCode.TOURNAMENT_SCORE_INVALID, "比分不能相同，羽毛球没有平局");
        }

        boolean first = m.getWinner() == null || m.getWinner() == Constants.WINNER_NONE;
        TournamentEntry winnerEntry = getEntry(winner == Constants.WINNER_A ? m.getTeamA() : m.getTeamB());
        TournamentEntry loserEntry = getEntry(winner == Constants.WINNER_A ? m.getTeamB() : m.getTeamA());

        m.setWinner(winner);
        m.setScoreA(scoreA);
        m.setScoreB(scoreB);
        if (first) {
            m.setRefereeId(operatorId);
        }
        matchMapper.updateById(m);

        // 第一场赛果出来，赛事从「已抽签」进入「进行中」
        if (Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_DRAWN)) {
            t.setStatus(Constants.TOURNAMENT_STATUS_RUNNING);
        }

        int stage = nz(m.getStage());
        switch (stage) {
            case Constants.TOURNAMENT_STAGE_GROUP ->
                    applyGroupScore(t, m, winnerEntry, loserEntry, scoreA, scoreB);
            case Constants.TOURNAMENT_STAGE_KNOCKOUT ->
                    applyKnockoutScore(t, m, winnerEntry, loserEntry);
            case Constants.TOURNAMENT_STAGE_BRONZE ->
                    applyBronzeScore(t, winnerEntry, loserEntry);
            case Constants.TOURNAMENT_STAGE_FINAL ->
                    applyFinalScore(t, winnerEntry, loserEntry);
            default -> throw new BizException(ErrorCode.SYSTEM_ERROR, "未知赛制阶段：" + stage);
        }
        tournamentMapper.updateById(t);

        log.info("赛事计分: tournament={}, {} match={} 胜方={} {}:{}",
                t.getId(), STAGE_NAMES.getOrDefault(stage, "阶段" + stage), matchId, winner, scoreA, scoreB);
        return Map.of("matchId", matchId, "winner", winner,
                "scoreA", scoreA == null ? 0 : scoreA,
                "scoreB", scoreB == null ? 0 : scoreB,
                "advanced", advancedStageText(t));
    }

    /**
     * 撤销本场：回滚本场战绩 + 清空所有后续轮次 + 参赛单位状态整体回退到「已抽签」。
     * <p>
     * 这样处理是为了保证任何时刻的赛程都自洽：不会出现「A 队刚被撤销，但他已经赢了下一轮」的脏数据，
     * 代价是裁判需要把后续轮次重新打一遍——对校园赛来说，这个取舍比脏数据划算得多。
     */
    private Map<String, Object> revoke(Tournament t, TournamentMatch m, Long operatorId) {
        if (m.getWinner() == null || m.getWinner() == Constants.WINNER_NONE) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "本场还没有结果，无需撤销");
        }
        int stage = nz(m.getStage());

        if (stage == Constants.TOURNAMENT_STAGE_GROUP) {
            int delta = pointDelta(m);
            boolean aWon = m.getWinner() == Constants.WINNER_A;
            TournamentEntry a = getEntry(m.getTeamA());
            TournamentEntry b = getEntry(m.getTeamB());
            if (a != null) {
                undoGroupStanding(a, aWon, delta);
                entryMapper.updateById(a);
            }
            if (b != null) {
                undoGroupStanding(b, !aWon, delta);
                entryMapper.updateById(b);
            }
            // 名次与晋级标记是本组全部赛果的产物，本场一撤就得重算
            for (TournamentEntry e : groupMembers(m.getGroupId())) {
                e.setGroupRank(null);
                e.setAdvance(0);
                entryMapper.updateById(e);
            }
            TournamentGroup group = groupMapper.selectById(m.getGroupId());
            if (group != null) {
                group.setStatus(Constants.TOURNAMENT_GROUP_RUNNING);
                groupMapper.updateById(group);
            }
        }

        rollbackDownstream(t, m);

        m.setWinner(Constants.WINNER_NONE);
        m.setScoreA(null);
        m.setScoreB(null);
        m.setRefereeId(null);
        matchMapper.updateById(m);
        tournamentMapper.updateById(t);

        log.info("撤销赛果: tournament={}, match={}, operator={}", t.getId(), m.getId(), operatorId);
        return Map.of("matchId", m.getId(), "winner", Constants.WINNER_NONE,
                "revoked", true, "advanced", advancedStageText(t));
    }

    /** 小组赛赛果：累计胜负与净胜分，组打满则排名定晋级，该组别全部小组完赛则开淘汰赛 */
    private void applyGroupScore(Tournament t, TournamentMatch m,
                                 TournamentEntry winnerEntry, TournamentEntry loserEntry,
                                 Integer scoreA, Integer scoreB) {
        int delta = (scoreA != null && scoreB != null) ? Math.abs(scoreA - scoreB) : 1;
        if (winnerEntry != null) {
            winnerEntry.setGroupWin(nz(winnerEntry.getGroupWin()) + 1);
            winnerEntry.setGroupPointDiff(nz(winnerEntry.getGroupPointDiff()) + delta);
            entryMapper.updateById(winnerEntry);
        }
        if (loserEntry != null) {
            loserEntry.setGroupLoss(nz(loserEntry.getGroupLoss()) + 1);
            loserEntry.setGroupPointDiff(nz(loserEntry.getGroupPointDiff()) - delta);
            entryMapper.updateById(loserEntry);
        }

        if (!groupAllFinished(m.getGroupId())) {
            return;
        }
        finishGroup(t, m.getGroupId());
        if (allGroupsFinished(t.getId(), nz(m.getFormat()))) {
            generateKnockoutFirstRound(t, nz(m.getFormat()));
        }
    }

    /** 本组赛果全部录入 → 组内排名 + 标晋级 */
    private void finishGroup(Tournament t, Long groupId) {
        List<TournamentEntry> members = groupMembers(groupId);
        if (members.isEmpty()) {
            return;
        }
        int advancePerGroup = nz(t.getAdvancePerGroup());
        List<TournamentEngine.GroupRanked> ranked = engine.rankGroup(members.stream()
                .map(e -> new TournamentEngine.GroupStanding(e.getId(),
                        nz(e.getGroupWin()), nz(e.getGroupLoss()), nz(e.getGroupPointDiff())))
                .toList());
        for (TournamentEngine.GroupRanked r : ranked) {
            TournamentEntry e = getEntry(r.entryId());
            if (e == null) {
                continue;
            }
            e.setGroupRank(r.rank());
            e.setAdvance(r.rank() <= advancePerGroup ? 1 : 0);
            entryMapper.updateById(e);
        }
        TournamentGroup group = groupMapper.selectById(groupId);
        if (group != null) {
            group.setStatus(Constants.TOURNAMENT_GROUP_DONE);
            groupMapper.updateById(group);
        }
    }

    /**
     * 生成淘汰赛首轮：晋级队按「小组名次 → 组名」排序后，用标准种子配对（1 vs N、2 vs N-1……）。
     * <p>
     * 之所以不用纯随机：同组前两名如果随机撞在一起就是首轮内耗，
     * 标准种子配对能保证同组的两队必然被拆到对阵表的两端。
     * 组名本身已经是随机抽签的结果，所以「谁在1 号位」依然是随机的。
     */
    private void generateKnockoutFirstRound(Tournament t, int format) {
        List<TournamentEntry> advanced = entriesOf(loadEntries(t.getId()), format).stream()
                .filter(e -> nz(e.getAdvance()) == 1)
                .sorted(Comparator.comparing(TournamentEntry::getGroupRank,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TournamentEntry::getGroupNo,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(TournamentEntry::getId))
                .toList();
        int size = engine.knockoutSize(advanced.size());

        for (int i = 0; i < size / 2; i++) {
            TournamentMatch m = newMatch(t.getId(), format, Constants.TOURNAMENT_STAGE_KNOCKOUT);
            m.setRoundNo(1);
            m.setRoundSize(size);
            m.setMatchNo(i + 1);
            m.setCourt(i + 1);
            m.setTeamA(advanced.get(i).getId());
            m.setTeamB(advanced.get(size - 1 - i).getId());
            matchMapper.insert(m);
        }
        for (TournamentEntry e : advanced) {
            e.setStatus(Constants.TOURNAMENT_ENTRY_ALIVE);
            entryMapper.updateById(e);
        }
        t.setStatus(Constants.TOURNAMENT_STATUS_RUNNING);
        log.info("生成淘汰赛首轮: tournament={}, {} {}强（{} 队晋级）",
                t.getId(), formatName(format), size, size);
    }

    /** 淘汰赛赛果：败者出局记名次，本轮打满就推进下一轮；四强打满则开季军赛 + 决赛 */
    private void applyKnockoutScore(Tournament t, TournamentMatch m,
                                    TournamentEntry winnerEntry, TournamentEntry loserEntry) {
        int roundSize = nz(m.getRoundSize());
        int format = nz(m.getFormat());
        int roundNo = nz(m.getRoundNo());

        if (winnerEntry != null) {
            winnerEntry.setStatus(Constants.TOURNAMENT_ENTRY_ALIVE);
            entryMapper.updateById(winnerEntry);
        }
        if (loserEntry != null) {
            loserEntry.setStatus(Constants.TOURNAMENT_ENTRY_OUT);
            loserEntry.setEliminatedStage(Constants.TOURNAMENT_STAGE_KNOCKOUT);
            // 四强败者还要打季军赛，名次等季军赛再定
            if (roundSize > 4) {
                loserEntry.setFinalRank(roundSize / 2 + 1);
            }
            entryMapper.updateById(loserEntry);
        }

        if (!roundAllFinished(t.getId(), format, Constants.TOURNAMENT_STAGE_KNOCKOUT, roundNo)) {
            return;
        }

        List<TournamentMatch> round = roundMatches(t.getId(), format,
                Constants.TOURNAMENT_STAGE_KNOCKOUT, roundNo);
        List<Long> winners = round.stream()
                .map(x -> x.getWinner() == Constants.WINNER_A ? x.getTeamA() : x.getTeamB())
                .filter(Objects::nonNull).toList();

        if (roundSize > 4) {
            // 普通轮次：用本轮胜者随机配对生成下一轮
            List<TournamentEngine.Pair> pairs = engine.shufflePairs(winners);
            for (int i = 0; i < pairs.size(); i++) {
                TournamentMatch next = newMatch(t.getId(), format, Constants.TOURNAMENT_STAGE_KNOCKOUT);
                next.setRoundNo(roundNo + 1);
                next.setRoundSize(winners.size());
                next.setMatchNo(i + 1);
                next.setCourt(i + 1);
                next.setTeamA(pairs.get(i).a());
                next.setTeamB(pairs.get(i).b());
                matchMapper.insert(next);
            }
            log.info("生成 {} {}强: tournament={}", formatName(format), winners.size(), t.getId());
            return;
        }

        // 四强打满：收官战——决赛争冠，季军赛争季军
        List<Long> losers = round.stream()
                .map(x -> x.getWinner() == Constants.WINNER_A ? x.getTeamB() : x.getTeamA())
                .filter(Objects::nonNull).toList();

        if (losers.size() >= 2) {
            TournamentMatch bronze = newMatch(t.getId(), format, Constants.TOURNAMENT_STAGE_BRONZE);
            bronze.setRoundNo(0);
            bronze.setRoundSize(0);
            bronze.setMatchNo(1);
            bronze.setCourt(1);
            bronze.setTeamA(losers.get(0));
            bronze.setTeamB(losers.get(1));
            matchMapper.insert(bronze);
        }
        if (winners.size() >= 2) {
            TournamentMatch finalMatch = newMatch(t.getId(), format, Constants.TOURNAMENT_STAGE_FINAL);
            finalMatch.setRoundNo(0);
            finalMatch.setRoundSize(0);
            finalMatch.setMatchNo(1);
            finalMatch.setCourt(1);
            finalMatch.setTeamA(winners.get(0));
            finalMatch.setTeamB(winners.get(1));
            matchMapper.insert(finalMatch);
        }
        log.info("四强赛毕，生成季军赛 + 决赛: tournament={}, {}", t.getId(), formatName(format));
    }

    /** 季军赛：胜者季军，负者第 4 */
    private void applyBronzeScore(Tournament t, TournamentEntry winnerEntry, TournamentEntry loserEntry) {
        if (winnerEntry != null) {
            winnerEntry.setStatus(Constants.TOURNAMENT_ENTRY_OUT);
            winnerEntry.setEliminatedStage(Constants.TOURNAMENT_STAGE_BRONZE);
            winnerEntry.setFinalRank(3);
            entryMapper.updateById(winnerEntry);
            t.setThirdEntryId(winnerEntry.getId());
        }
        if (loserEntry != null) {
            loserEntry.setStatus(Constants.TOURNAMENT_ENTRY_OUT);
            loserEntry.setEliminatedStage(Constants.TOURNAMENT_STAGE_BRONZE);
            loserEntry.setFinalRank(4);
            entryMapper.updateById(loserEntry);
            t.setFourthEntryId(loserEntry.getId());
        }
    }

    /** 决赛：胜者冠军、负者亚军，赛事收官（要等该组别决赛打完才把整个赛事置为已结束） */
    private void applyFinalScore(Tournament t, TournamentEntry winnerEntry, TournamentEntry loserEntry) {
        if (winnerEntry != null) {
            winnerEntry.setStatus(Constants.TOURNAMENT_ENTRY_OUT);
            winnerEntry.setEliminatedStage(Constants.TOURNAMENT_STAGE_FINAL);
            winnerEntry.setFinalRank(1);
            entryMapper.updateById(winnerEntry);
            t.setChampionEntryId(winnerEntry.getId());
        }
        if (loserEntry != null) {
            loserEntry.setStatus(Constants.TOURNAMENT_ENTRY_OUT);
            loserEntry.setEliminatedStage(Constants.TOURNAMENT_STAGE_FINAL);
            loserEntry.setFinalRank(2);
            entryMapper.updateById(loserEntry);
            t.setRunnerUpEntryId(loserEntry.getId());
        }
        if (allFormatsFinished(t)) {
            t.setStatus(Constants.TOURNAMENT_STATUS_FINISHED);
            log.info("赛事结束: tournament={}, 冠军参赛单位={}", t.getId(),
                    t.getChampionEntryId() == null ? null : t.getChampionEntryId());
        }
    }

    /* ==================== 权限 ==================== */

    private Tournament requireTournament(Long id) {
        Tournament t = tournamentMapper.selectById(id);
        if (t == null) {
            throw new BizException(ErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return t;
    }

    private boolean isAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        User u = userMapper.selectById(userId);
        return u != null && nz(u.getIsAdmin()) == Constants.ADMIN_YES;
    }

    private void requireAdmin(Long userId) {
        if (!isAdmin(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅管理员可操作");
        }
    }

    /** 管理员或赛事创建人（创建人也是管理员才能建赛事，这里主要覆盖「非管理员但自己建的」的历史数据） */
    private void requireOwnerOrAdmin(Tournament t, Long userId) {
        if (isAdmin(userId) || Objects.equals(t.getCreatorId(), userId)) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "仅赛事创建人或管理员可操作");
    }

    /**
     * 谁能录赛果：管理员、赛事创建人、本场参赛方。
     * <p>
     * 刻意<b>不</b>放开给所有登录用户——淘汰赛每一场都直接决定名次，
     * 让陌生人能随手改结果风险太大。
     */
    private void requireScorePermission(Tournament t, TournamentMatch m, Long operatorId) {
        if (operatorId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (isAdmin(operatorId) || Objects.equals(t.getCreatorId(), operatorId)) {
            return;
        }
        for (Long teamId : List.of(m.getTeamA(), m.getTeamB())) {
            if (teamId == null) {
                continue;
            }
            TournamentEntry e = getEntry(teamId);
            if (e != null && (Objects.equals(e.getUserId(), operatorId)
                    || Objects.equals(e.getPartnerId(), operatorId))) {
                return;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "只有本场参赛方、管理员或赛事创建人才能录入赛果");
    }

    /** 组别性别规则：男双两男、女双两女、混双一男一女；性别未填的引导去补资料 */
    private void checkGenderRule(int format, User captain, User partner) {
        Integer g1 = captain.getGender();
        Integer g2 = partner.getGender();
        if (g1 == null || g2 == null || g1 == 0 || g2 == 0) {
            throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID,
                    "参赛组别要求明确性别，请先到「我的资料」补全性别");
        }
        if (format == Constants.FORMAT_MEN_DOUBLES && !(g1 == Constants.GENDER_MALE && g2 == Constants.GENDER_MALE)) {
            throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID, "男双要求两位男生");
        }
        if (format == Constants.FORMAT_WOMEN_DOUBLES && !(g1 == Constants.GENDER_FEMALE && g2 == Constants.GENDER_FEMALE)) {
            throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID, "女双要求两位女生");
        }
        if (format == Constants.FORMAT_MIXED_DOUBLES && g1.equals(g2)) {
            throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID, "混双要求一男一女");
        }
    }

    /* ==================== 数据装配 ==================== */

    private List<TournamentEntry> loadEntries(Long tournamentId) {
        return entryMapper.selectList(new LambdaQueryWrapper<TournamentEntry>()
                .eq(TournamentEntry::getTournamentId, tournamentId)
                .orderByAsc(TournamentEntry::getId));
    }

    private List<TournamentGroup> loadGroups(Long tournamentId) {
        return groupMapper.selectList(new LambdaQueryWrapper<TournamentGroup>()
                .eq(TournamentGroup::getTournamentId, tournamentId)
                .orderByAsc(TournamentGroup::getFormat)
                .orderByAsc(TournamentGroup::getSeq));
    }

    private List<TournamentMatch> loadMatches(Long tournamentId) {
        return matchMapper.selectList(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getTournamentId, tournamentId)
                .orderByAsc(TournamentMatch::getStage)
                .orderByAsc(TournamentMatch::getRoundNo)
                .orderByAsc(TournamentMatch::getMatchNo));
    }

    /** 参赛单位涉及到的所有队员（队长 + 搭档）一次查完，返回 userId → user */
    private Map<Long, User> loadUsers(List<TournamentEntry> entries) {
        Set<Long> ids = new LinkedHashSet<>();
        for (TournamentEntry e : entries) {
            if (e.getUserId() != null) {
                ids.add(e.getUserId());
            }
            if (e.getPartnerId() != null) {
                ids.add(e.getPartnerId());
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));
    }

    private List<TournamentEntry> entriesOf(List<TournamentEntry> all, int format) {
        return all.stream().filter(e -> nz(e.getFormat()) == format).toList();
    }

    private TournamentEntry getEntry(Long id) {
        return id == null ? null : entryMapper.selectById(id);
    }

    private List<TournamentEntry> groupMembers(Long groupId) {
        if (groupId == null) {
            return new ArrayList<>();
        }
        return entryMapper.selectList(new LambdaQueryWrapper<TournamentEntry>()
                .eq(TournamentEntry::getGroupId, groupId)
                .orderByAsc(TournamentEntry::getSeedNo)
                .orderByAsc(TournamentEntry::getId));
    }

    private TournamentGroup findGroup(Long tournamentId, int format, String groupNo) {
        return groupMapper.selectOne(new LambdaQueryWrapper<TournamentGroup>()
                .eq(TournamentGroup::getTournamentId, tournamentId)
                .eq(TournamentGroup::getFormat, format)
                .eq(TournamentGroup::getGroupNo, groupNo)
                .last("limit 1"));
    }

    private TournamentMatch newMatch(Long tournamentId, int format, int stage) {
        TournamentMatch m = new TournamentMatch();
        m.setTournamentId(tournamentId);
        m.setFormat(format);
        m.setStage(stage);
        m.setWinner(Constants.WINNER_NONE);
        return m;
    }

    /** 我报名过的赛事 id 集合（用于列表页标记「已报名」） */
    private List<Long> myTournamentIds(List<Long> tournamentIds, Long userId) {
        if (userId == null || tournamentIds.isEmpty()) {
            return new ArrayList<>();
        }
        return entryMapper.selectList(new LambdaQueryWrapper<TournamentEntry>()
                        .in(TournamentEntry::getTournamentId, tournamentIds)
                        .and(w -> w.eq(TournamentEntry::getUserId, userId)
                                .or().eq(TournamentEntry::getPartnerId, userId)))
                .stream().map(TournamentEntry::getTournamentId).distinct().toList();
    }

    /** 各赛事的冠军参赛单位（用于列表页展示「冠军」字样） */
    private Map<Long, TournamentEntry> championEntries(List<Long> tournamentIds) {
        List<TournamentEntry> champs = entryMapper.selectList(new LambdaQueryWrapper<TournamentEntry>()
                .in(TournamentEntry::getTournamentId, tournamentIds)
                .eq(TournamentEntry::getFinalRank, 1));
        Map<Long, TournamentEntry> out = new LinkedHashMap<>();
        for (TournamentEntry e : champs) {
            out.putIfAbsent(e.getTournamentId(), e);
        }
        return out;
    }

    /* ==================== 状态判定 ==================== */

    private boolean isDrawn(Tournament t) {
        int status = nz(t.getStatus());
        return status != Constants.TOURNAMENT_STATUS_OPEN;
    }

    /** 本组所有对阵都出了结果 */
    private boolean groupAllFinished(Long groupId) {
        if (groupId == null) {
            return false;
        }
        return matchMapper.selectCount(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getGroupId, groupId)
                .eq(TournamentMatch::getWinner, Constants.WINNER_NONE)) == 0;
    }

    /** 本轮所有对阵都出了结果 */
    private boolean roundAllFinished(Long tournamentId, int format, int stage, int roundNo) {
        return matchMapper.selectCount(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getTournamentId, tournamentId)
                .eq(TournamentMatch::getFormat, format)
                .eq(TournamentMatch::getStage, stage)
                .eq(TournamentMatch::getRoundNo, roundNo)
                .eq(TournamentMatch::getWinner, Constants.WINNER_NONE)) == 0;
    }

    /** 该组别所有小组都完赛（用于触发淘汰赛首轮） */
    private boolean allGroupsFinished(Long tournamentId, int format) {
        List<TournamentGroup> groups = groupMapper.selectList(new LambdaQueryWrapper<TournamentGroup>()
                .eq(TournamentGroup::getTournamentId, tournamentId)
                .eq(TournamentGroup::getFormat, format));
        if (groups.isEmpty()) {
            return false;
        }
        for (TournamentGroup g : groups) {
            if (nz(g.getStatus()) != Constants.TOURNAMENT_GROUP_DONE) {
                return false;
            }
        }
        return true;
    }

    /** 所有开赛组别都打完了才把整个赛事置为已结束 */
    private boolean allFormatsFinished(Tournament t) {
        List<Integer> formats = parseFormats(t.getFormatList());
        for (int format : formats) {
            long pending = matchMapper.selectCount(new LambdaQueryWrapper<TournamentMatch>()
                    .eq(TournamentMatch::getTournamentId, t.getId())
                    .eq(TournamentMatch::getFormat, format)
                    .eq(TournamentMatch::getWinner, Constants.WINNER_NONE));
            boolean hasMatches = matchMapper.selectCount(new LambdaQueryWrapper<TournamentMatch>()
                    .eq(TournamentMatch::getTournamentId, t.getId())
                    .eq(TournamentMatch::getFormat, format)) > 0;
            if (hasMatches && pending > 0) {
                return false;
            }
        }
        return true;
    }

    private List<TournamentMatch> roundMatches(Long tournamentId, int format, int stage, int roundNo) {
        return matchMapper.selectList(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getTournamentId, tournamentId)
                .eq(TournamentMatch::getFormat, format)
                .eq(TournamentMatch::getStage, stage)
                .eq(TournamentMatch::getRoundNo, roundNo)
                .orderByAsc(TournamentMatch::getMatchNo));
    }

    /**
     * 清空本场之后的所有轮次，并把该组别全部参赛单位的状态回退到「已抽签」。
     * <p>
     * 小组赛成绩（胜场 / 净胜分 / 组内名次 / 晋级标记）保留不动——那部分是独立成立的，
     * 被清掉的是「进入淘汰赛之后」才产生的状态与名次。
     */
    private void rollbackDownstream(Tournament t, TournamentMatch m) {
        int stage = nz(m.getStage());
        int roundNo = nz(m.getRoundNo());
        int format = nz(m.getFormat());

        List<TournamentMatch> later = matchMapper.selectList(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getTournamentId, t.getId())
                .eq(TournamentMatch::getFormat, format)
                .and(w -> w.gt(TournamentMatch::getStage, stage)
                        .or(x -> x.eq(TournamentMatch::getStage, stage)
                                .gt(TournamentMatch::getRoundNo, roundNo))));
        if (!later.isEmpty()) {
            matchMapper.delete(new LambdaQueryWrapper<TournamentMatch>()
                    .in(TournamentMatch::getId, later.stream().map(TournamentMatch::getId).toList()));
        }

        for (TournamentEntry e : entriesOf(loadEntries(t.getId()), format)) {
            if (nz(e.getStatus()) == Constants.TOURNAMENT_ENTRY_REGISTERED) {
                continue;
            }
            e.setStatus(Constants.TOURNAMENT_ENTRY_DRAWN);
            e.setEliminatedStage(null);
            e.setFinalRank(null);
            entryMapper.updateById(e);
        }

        boolean hasKnockout = matchMapper.selectCount(new LambdaQueryWrapper<TournamentMatch>()
                .eq(TournamentMatch::getTournamentId, t.getId())
                .eq(TournamentMatch::getFormat, format)
                .in(TournamentMatch::getStage, List.of(Constants.TOURNAMENT_STAGE_KNOCKOUT,
                        Constants.TOURNAMENT_STAGE_BRONZE, Constants.TOURNAMENT_STAGE_FINAL))) > 0;
        t.setChampionEntryId(null);
        t.setRunnerUpEntryId(null);
        t.setThirdEntryId(null);
        t.setFourthEntryId(null);
        t.setStatus(hasKnockout ? Constants.TOURNAMENT_STATUS_RUNNING : Constants.TOURNAMENT_STATUS_DRAWN);
    }

    /** 撤销一场小组赛：反向扣回胜场 / 负场 / 净胜分 */
    private void undoGroupStanding(TournamentEntry e, boolean won, int delta) {
        if (won) {
            e.setGroupWin(Math.max(0, nz(e.getGroupWin()) - 1));
            e.setGroupPointDiff(nz(e.getGroupPointDiff()) - delta);
        } else {
            e.setGroupLoss(Math.max(0, nz(e.getGroupLoss()) - 1));
            e.setGroupPointDiff(nz(e.getGroupPointDiff()) + delta);
        }
    }

    /** 本场产生的净胜分；没填比分时按 1 计 */
    private int pointDelta(TournamentMatch m) {
        if (m.getScoreA() != null && m.getScoreB() != null) {
            return Math.max(1, Math.abs(m.getScoreA() - m.getScoreB()));
        }
        return 1;
    }

    /** 给前端的一句话进度提示：当前赛程推进到哪一阶段 */
    private String advancedStageText(Tournament t) {
        int status = nz(t.getStatus());
        return switch (status) {
            case Constants.TOURNAMENT_STATUS_OPEN -> "报名中";
            case Constants.TOURNAMENT_STATUS_DRAWN -> "小组赛进行中";
            case Constants.TOURNAMENT_STATUS_RUNNING -> "淘汰赛进行中";
            case Constants.TOURNAMENT_STATUS_FINISHED -> "赛事已结束，冠军已产生";
            default -> "赛事已取消";
        };
    }

    /* ==================== 搭档查找 ==================== */

    /**
     * 按注册账号找搭档。
     *
     * <p>为什么用账号而不是搜索框：赛事报名里搭档必须是<b>站内已注册的球友</b>，
     * 而系统本来就没有「搜用户」的能力；用账号精确匹配是改动最小、也最不容易选错人的方案。
     *
     * <p>只回传「姓名 / 头像 / 性别」三样——够球友确认「没选错人」，但不会顺着账号扒出联系方式。
     * 性别一并返回是为了让前端能在提交前就提示「混双需要一男一女」，少一次往返。
     */
    public TournamentMatchItem.MemberBrief lookupPartner(String account, Long operatorId) {
        if (operatorId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String key = account == null ? "" : account.trim();
        if (key.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请填写搭档的注册账号");
        }
        User partner = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getAccount, key)
                .last("limit 1"));
        if (partner == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND, "没有找到账号为「" + key + "」的球友");
        }
        if (Objects.equals(partner.getId(), operatorId)) {
            throw new BizException(ErrorCode.TOURNAMENT_PARTNER_INVALID, "不能把自己选成搭档");
        }
        return memberBrief(partner, partner.getId());
    }

    /* ==================== 视图对象转换 ==================== */

    private TournamentMatchItem toMatchItem(TournamentMatch m, Map<Long, TournamentEntry> entryMap,
                                            Map<Long, User> users, Long currentUserId) {
        TournamentEntry a = entryMap.get(m.getTeamA());
        TournamentEntry b = entryMap.get(m.getTeamB());
        return TournamentMatchItem.builder()
                .id(m.getId())
                .stage(m.getStage())
                .roundNo(m.getRoundNo())
                .roundSize(m.getRoundSize())
                .roundName(roundName(m))
                .groupNo(a != null ? a.getGroupNo() : null)
                .matchNo(m.getMatchNo())
                .court(m.getCourt())
                .teamA(toTeamItem(a, users))
                .teamB(toTeamItem(b, users))
                .winner(m.getWinner())
                .scoreA(m.getScoreA())
                .scoreB(m.getScoreB())
                .refereeId(m.getRefereeId())
                .refereeName(displayName(users.get(m.getRefereeId()), m.getRefereeId()))
                .finished(m.getWinner() != null && m.getWinner() != Constants.WINNER_NONE)
                .involved(isInvolved(a, currentUserId) || isInvolved(b, currentUserId))
                .build();
    }

    /** 当前登录用户是不是这支队伍的一员（队长或搭档） */
    private boolean isInvolved(TournamentEntry e, Long userId) {
        return e != null && userId != null
                && (Objects.equals(e.getUserId(), userId) || Objects.equals(e.getPartnerId(), userId));
    }

    private TournamentMatchItem.TeamItem toTeamItem(TournamentEntry e, Map<Long, User> users) {
        if (e == null) {
            return null;
        }
        return TournamentMatchItem.TeamItem.builder()
                .entryId(e.getId())
                .name(teamName(e, users))
                .members(membersOf(e, users))
                .groupNo(e.getGroupNo())
                .advance(nz(e.getAdvance()) == 1)
                .finalRank(e.getFinalRank())
                .build();
    }

    private TournamentMatchItem.MemberBrief memberBrief(User u, Long userId) {
        return TournamentMatchItem.MemberBrief.builder()
                .userId(userId)
                .name(displayName(u, userId))
                .avatar(u == null ? null : u.getAvatar())
                .gender(u == null ? 0 : nz(u.getGender()))
                .build();
    }

    private TournamentBracketResponse.StandingItem toStanding(TournamentEntry e, Map<Long, User> users) {
        return TournamentBracketResponse.StandingItem.builder()
                .entryId(e.getId())
                .name(teamName(e, users))
                .members(membersOf(e, users))
                .rank(nz(e.getGroupRank()))
                .win(nz(e.getGroupWin()))
                .loss(nz(e.getGroupLoss()))
                .pointDiff(nz(e.getGroupPointDiff()))
                .advance(nz(e.getAdvance()) == 1)
                .build();
    }

    private TournamentBracketResponse.RankItem toRankItem(TournamentEntry e, Map<Long, User> users) {
        return TournamentBracketResponse.RankItem.builder()
                .rank(e.getFinalRank())
                .entryId(e.getId())
                .name(teamName(e, users))
                .members(membersOf(e, users))
                .groupNo(e.getGroupNo())
                .eliminatedText(eliminatedText(e))
                .build();
    }

    private TournamentDetailResponse.MyEntryItem toMyEntry(TournamentEntry e, Map<Long, User> users) {
        return TournamentDetailResponse.MyEntryItem.builder()
                .entryId(e.getId())
                .format(nz(e.getFormat()))
                .formatName(formatName(nz(e.getFormat())))
                .name(teamName(e, users))
                .groupNo(e.getGroupNo())
                .status(e.getStatus())
                .statusText(ENTRY_STATUS_NAMES.getOrDefault(nz(e.getStatus()), "未知"))
                .finalRank(e.getFinalRank())
                .eliminatedText(eliminatedText(e))
                .build();
    }

    private List<TournamentDetailResponse.PodiumItem> podium(Tournament t,
                                                           List<TournamentEntry> entries,
                                                           Map<Long, User> users) {
        Map<Long, TournamentEntry> byId = entries.stream()
                .collect(Collectors.toMap(TournamentEntry::getId, e -> e, (a, b) -> a));
        List<TournamentDetailResponse.PodiumItem> out = new ArrayList<>();
        for (int rank : List.of(1, 2, 3, 4)) {
            Long entryId = switch (rank) {
                case 1 -> t.getChampionEntryId();
                case 2 -> t.getRunnerUpEntryId();
                case 3 -> t.getThirdEntryId();
                default -> t.getFourthEntryId();
            };
            TournamentEntry e = entryId == null ? null : byId.get(entryId);
            if (e == null) {
                continue;
            }
            out.add(TournamentDetailResponse.PodiumItem.builder()
                    .rank(rank)
                    .rankName(RANK_NAMES.get(rank))
                    .format(nz(e.getFormat()))
                    .formatName(formatName(nz(e.getFormat())))
                    .name(teamName(e, users))
                    .members(membersOf(e, users))
                    .build());
        }
        return out;
    }

    /** 把淘汰赛（stage=2）+ 季军赛 + 决赛按轮次聚成 RoundBoard 列表 */
    private List<TournamentBracketResponse.RoundBoard> knockoutRounds(
            List<TournamentMatch> matches, Map<Long, TournamentEntry> entryMap,
            Map<Long, User> users, Long currentUserId, int stage) {
        Map<Integer, List<TournamentMatch>> byRound = matches.stream()
                .filter(m -> nz(m.getStage()) == stage)
                .collect(Collectors.groupingBy(TournamentMatch::getRoundNo,
                        LinkedHashMap::new, Collectors.toList()));
        List<TournamentBracketResponse.RoundBoard> out = new ArrayList<>();
        for (Map.Entry<Integer, List<TournamentMatch>> e : byRound.entrySet()) {
            List<TournamentMatch> round = e.getValue().stream()
                    .sorted(Comparator.comparing(TournamentMatch::getMatchNo)).toList();
            List<TournamentMatchItem> items = round.stream()
                    .map(m -> toMatchItem(m, entryMap, users, currentUserId)).toList();
            TournamentMatch sample = round.get(0);
            out.add(TournamentBracketResponse.RoundBoard.builder()
                    .roundNo(e.getKey())
                    .roundSize(nz(sample.getRoundSize()))
                    .roundName(roundName(sample))
                    .finished(round.stream().allMatch(m -> m.getWinner() != null
                            && m.getWinner() != Constants.WINNER_NONE))
                    .matches(items)
                    .build());
        }
        return out;
    }

    /* ==================== 小工具 ==================== */

    private List<TournamentMatchItem.MemberBrief> membersOf(TournamentEntry e, Map<Long, User> users) {
        List<TournamentMatchItem.MemberBrief> members = new ArrayList<>();
        members.add(memberBrief(users.get(e.getUserId()), e.getUserId()));
        if (e.getPartnerId() != null) {
            members.add(memberBrief(users.get(e.getPartnerId()), e.getPartnerId()));
        }
        return members;
    }

    /** 队名：双人「A / B」，单人直接是姓名 */
    private String teamName(TournamentEntry e, Map<Long, User> users) {
        if (e == null) {
            return "待定";
        }
        String captain = displayName(users.get(e.getUserId()), e.getUserId());
        if (e.getPartnerId() == null) {
            return captain;
        }
        return captain + " / " + displayName(users.get(e.getPartnerId()), e.getPartnerId());
    }

    /** 展示名统一口径：优先昵称，回退姓名，都没有则「球友#id」 */
    private String displayName(User u, Long userId) {
        if (u != null) {
            if (u.getNickname() != null && !u.getNickname().isBlank()) {
                return u.getNickname().trim();
            }
            if (u.getName() != null && !u.getName().isBlank()) {
                return u.getName().trim();
            }
        }
        return "球友#" + userId;
    }

    /** 轮次中文名：小组赛「第 N 轮」/ 淘汰赛「十六强」/ 季军赛 / 决赛 */
    private String roundName(TournamentMatch m) {
        int stage = nz(m.getStage());
        if (stage == Constants.TOURNAMENT_STAGE_GROUP) {
            return "第 " + nz(m.getRoundNo()) + " 轮";
        }
        if (stage == Constants.TOURNAMENT_STAGE_BRONZE) {
            return "季军赛";
        }
        if (stage == Constants.TOURNAMENT_STAGE_FINAL) {
            return "决赛";
        }
        return KNOCKOUT_ROUND_NAMES.getOrDefault(nz(m.getRoundSize()), nz(m.getRoundSize()) + " 强");
    }

    /** 止步说明：前三名不展示；淘汰赛按止步轮次，季军赛 / 决赛按结果 */
    private String eliminatedText(TournamentEntry e) {
        Integer rank = e.getFinalRank();
        if (rank != null && rank <= 3) {
            return null;
        }
        int stage = nz(e.getEliminatedStage());
        return switch (stage) {
            case Constants.TOURNAMENT_STAGE_GROUP -> "小组赛止步";
            case Constants.TOURNAMENT_STAGE_KNOCKOUT -> "止步" + knockoutRoundNameOf(e);
            case Constants.TOURNAMENT_STAGE_BRONZE -> "季军赛";
            case Constants.TOURNAMENT_STAGE_FINAL -> "决赛";
            default -> null;
        };
    }

    private String knockoutRoundNameOf(TournamentEntry e) {
        // 名次反推轮次：8 强淘汰→名次 5，16 强淘汰→名次 9
        Integer rank = e.getFinalRank();
        if (rank == null) {
            return "";
        }
        int size = (rank - 1) * 2;
        return KNOCKOUT_ROUND_NAMES.getOrDefault(size, size + " 强");
    }

    private String statusText(Integer status) {
        return TOURNAMENT_STATUS_NAMES.getOrDefault(nz(status), "未知");
    }

    private String formatName(int format) {
        return FORMAT_NAMES.getOrDefault(format, "未知组别");
    }

    /** 每队人数：单打 1，其余 2 */
    private int teamSize(int format) {
        return format == Constants.FORMAT_SINGLES ? 1 : 2;
    }

    private List<Integer> parseFormats(String formatList) {
        if (formatList == null || formatList.isBlank()) {
            return List.of(Constants.FORMAT_SINGLES, Constants.FORMAT_MEN_DOUBLES,
                    Constants.FORMAT_WOMEN_DOUBLES, Constants.FORMAT_MIXED_DOUBLES);
        }
        List<Integer> out = new ArrayList<>();
        for (String s : formatList.split(",")) {
            try {
                int f = Integer.parseInt(s.trim());
                if (FORMAT_NAMES.containsKey(f)) {
                    out.add(f);
                }
            } catch (NumberFormatException ignored) {
                // 历史脏数据直接跳过，不让整个赛事读不出来
            }
        }
        return out.isEmpty() ? List.of(Constants.FORMAT_SINGLES) : out;
    }

    private List<Integer> normalizeFormats(List<Integer> formats) {
        if (formats == null || formats.isEmpty()) {
            return List.of(Constants.FORMAT_SINGLES, Constants.FORMAT_MEN_DOUBLES,
                    Constants.FORMAT_WOMEN_DOUBLES, Constants.FORMAT_MIXED_DOUBLES);
        }
        List<Integer> out = new ArrayList<>(new LinkedHashSet<>(formats));
        for (int f : out) {
            if (!FORMAT_NAMES.containsKey(f)) {
                throw new BizException(ErrorCode.PARAM_ERROR, "未知组别：" + f);
            }
        }
        return out.stream().sorted().toList();
    }

    /** 人数上限合计：各组别上限 × 每队人数（前端展示「限 XX 人」） */
    private int peopleUpperBound(List<Integer> formats, int maxEntries) {
        return formats.stream().mapToInt(this::teamSize).sum() * maxEntries;
    }

    /** 地点文案：优先赛事自己填的，其次关联球场的名称 */
    private String resolveLocationName(Tournament t) {
        if (t.getLocation() != null && !t.getLocation().isBlank()) {
            return t.getLocation();
        }
        if (t.getCourtId() != null) {
            Court court = courtMapper.selectById(t.getCourtId());
            if (court != null) {
                return court.getName();
            }
        }
        return null;
    }

    /** Integer 可能为 null，统一给个 0，省掉满屏的空判断 */
    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private int nz(Long v) {
        return v == null ? 0 : v.intValue();
    }
}