package com.yuyue.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuyue.common.Constants;
import com.yuyue.common.ErrorCode;
import com.yuyue.dto.TournamentBracketResponse;
import com.yuyue.dto.TournamentCreateRequest;
import com.yuyue.dto.TournamentDetailResponse;
import com.yuyue.dto.TournamentEntryItem;
import com.yuyue.dto.TournamentEntryRequest;
import com.yuyue.dto.TournamentListItem;
import com.yuyue.dto.TournamentManageItem;
import com.yuyue.dto.TournamentMatchItem;
import com.yuyue.dto.TournamentMatchesResponse;
import com.yuyue.dto.TournamentScoreRequest;
import com.yuyue.dto.TournamentSimulateRequest;
import com.yuyue.dto.TournamentUpdateRequest;
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
import com.yuyue.util.MatchFormatUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
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
    private final PermissionService permissionService;

    /* ==================== 文案映射 ==================== */

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

    /**
     * 管理端赛事列表（PC 官网管理台）。
     *
     * <p>与广场视角的三点差异：
     * <ol>
     *   <li><b>含已取消赛事</b>——管理员需要看到历史，包括被取消的；</li>
     *   <li><b>可按状态 / 名称筛选</b>——赛事多了以后必须能筛；</li>
     *   <li><b>带出每组别报名明细数与「淘汰赛规模是否合法」</b>——
     *       抽签前就能看出当前配置凑不凑得出完整签表，不用点了才知道报错。</li>
     * </ol>
     */
    public List<TournamentManageItem> manageList(Integer status, String keyword, Long operatorId) {
        requireAdmin(operatorId);
        String kw = keyword == null ? null : keyword.trim();

        List<Tournament> list = tournamentMapper.selectList(new LambdaQueryWrapper<Tournament>()
                .orderByAsc(Tournament::getStatus)
                .orderByDesc(Tournament::getId));
        List<Tournament> filtered = list.stream()
                .filter(t -> status == null || nz(t.getStatus()) == status)
                .filter(t -> kw == null || kw.isEmpty()
                        || (t.getTitle() != null && t.getTitle().contains(kw))
                        || (t.getLocation() != null && t.getLocation().contains(kw)))
                .toList();
        if (filtered.isEmpty()) {
            return new ArrayList<>();
        }

        List<Long> ids = filtered.stream().map(Tournament::getId).toList();
        Map<Long, List<TournamentEntry>> entryByTournament = entryMapper.selectList(
                        new LambdaQueryWrapper<TournamentEntry>().in(TournamentEntry::getTournamentId, ids))
                .stream()
                .collect(Collectors.groupingBy(TournamentEntry::getTournamentId));

        // 创建人姓名：一次把涉及的 user 捞齐
        Set<Long> creatorIds = filtered.stream().map(Tournament::getCreatorId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> creators = creatorIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(creatorIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));

        List<TournamentManageItem> out = new ArrayList<>(filtered.size());
        for (Tournament t : filtered) {
            List<TournamentEntry> entries = entryByTournament.getOrDefault(t.getId(), List.of());
            Map<Long, User> users = loadUsers(entries);
            List<Integer> formats = parseFormats(t.getFormatList());
            int groupSize = nz(t.getGroupSize());
            int advance = nz(t.getAdvancePerGroup());
            int maxEntries = nz(t.getMaxEntries());
            int groupRoundsCfg = nz(t.getGroupRounds());
            int knockoutRoundsCfg = nz(t.getKnockoutRounds());

            List<TournamentManageItem.FormatCount> counts = new ArrayList<>();
            for (int format : formats) {
                int inFormat = (int) entries.stream()
                        .filter(e -> nz(e.getFormat()) == format).count();
                counts.add(buildFormatCount(format, inFormat, groupSize, advance, maxEntries,
                        groupRoundsCfg, knockoutRoundsCfg));
            }

            TournamentEntry champion = entries.stream()
                    .filter(e -> Objects.equals(e.getFinalRank(), 1)).findFirst().orElse(null);

            out.add(TournamentManageItem.builder()
                    .id(t.getId())
                    .title(t.getTitle())
                    .location(t.getLocation())
                    .playDate(t.getPlayDate())
                    .remark(t.getRemark())
                    .status(nz(t.getStatus()))
                    .statusText(statusText(t.getStatus()))
                    .formats(formats)
                    .formatNames(formats.stream().map(this::formatName).toList())
                    .groupSize(groupSize)
                    .advancePerGroup(advance)
                    .maxEntries(maxEntries)
                    .groupRounds(groupRoundsCfg)
                    .knockoutRounds(knockoutRoundsCfg)
                    .entryCount(entries.size())
                    .formatCounts(counts)
                    .creatorId(t.getCreatorId())
                    .creatorName(creatorName(creators.get(t.getCreatorId()), t.getCreatorId()))
                    .drawn(isDrawn(t))
                    .championName(champion == null ? null : teamName(champion, users))
                    .createTime(t.getCreateTime())
                    .build());
        }
        return out;
    }

    /** 淘汰赛规模是否合法（≥4、≤256 且为 2 的幂）——与引擎的校验口径保持一致 */
    private boolean knockoutSizeValid(int advanceTotal) {
        if (advanceTotal < Constants.TOURNAMENT_MIN_KNOCKOUT_SIZE
                || advanceTotal > Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE) {
            return false;
        }
        int size = Integer.highestOneBit(advanceTotal);
        return size == advanceTotal;
    }

    /**
     * 抽签后的各组人数分布。
     * <p>与 {@link TournamentEngine#drawGroups} 的「均摊」策略一致（只是不打乱顺序）：
     * 余数多的组先多 1 队，因此不会出现只有 1 人的组。
     */
    private List<Integer> groupSizes(int total, int groupSize) {
        List<Integer> sizes = new ArrayList<>();
        if (groupSize < 2 || total < 2) {
            return sizes;
        }
        int count = engine.groupCount(total, groupSize);
        int base = total / count;
        int extra = total % count;
        for (int i = 0; i < count; i++) {
            sizes.add(base + (i < extra ? 1 : 0));
        }
        return sizes;
    }

    /**
     * 算一个组别的「赛程结构」：小组赛几轮、淘汰赛几轮、一共打多少场。
     *
     * <p>管理员在抽签前最想知道的其实是这件事——「16 队、每组 4 人、每组 2 名晋级」
     * 到底要打几轮、共几场，配置完就该看得见，而不是等抽完签才发现场次比想象多。
     *
     * @param groupRoundsCfg    小组赛轮数配置（0 = 自动打满单循环）
     * @param knockoutRoundsCfg 淘汰赛轮数配置（0 = 自动按晋级人数推导）
     */
    private TournamentManageItem.FormatCount buildFormatCount(int format, int entryCount,
                                                             int groupSize, int advance, int maxEntries,
                                                             int groupRoundsCfg, int knockoutRoundsCfg) {
        List<Integer> sizes = groupSizes(entryCount, groupSize);
        int groupCount = sizes.size();
        int advanceTotal = groupCount * advance;

        // 小组赛轮数：指定就用指定的，否则按满轮（各组并行，取轮数最多的那个组）
        int fullRounds = sizes.stream().mapToInt(engine::groupRounds).max().orElse(0);
        int groupRounds = groupRoundsCfg > 0 ? groupRoundsCfg : fullRounds;
        // 场次按「轮数 × 每轮场次」算，多循环时每遍都是完整满轮结构
        int groupMatches = 0;
        for (int size : sizes) {
            groupMatches += engine.groupTotalMatches(size, groupRoundsCfg);
        }

        // 淘汰赛规模：指定轮数则规模 = 2^(轮数+1)，晋级人数超出时自动扩容
        int size = engine.knockoutBracketSize(advanceTotal, knockoutRoundsCfg);
        int koRounds = size >= 4 ? engine.knockoutRounds(size) : 0;
        int koMatches = engine.knockoutMatches(size);
        // 季军赛 + 决赛；晋级不足 4 队时没有淘汰赛轮，只有决赛一场
        int finalMatches = size >= 4 ? 2 : (size == 2 ? 1 : 0);
        // 签位多于晋级队时，多出来的位置就是首轮轮空
        int byeCount = Math.max(0, size - advanceTotal);

        return TournamentManageItem.FormatCount.builder()
                .format(format)
                .formatName(formatName(format))
                .entryCount(entryCount)
                .maxEntries(maxEntries)
                .full(entryCount >= maxEntries)
                .knockoutSize(size)
                .knockoutSizeValid(size >= 4 && size <= Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE)
                .groupCount(groupCount)
                .teamSizePerGroup(sizes.stream().mapToInt(Integer::intValue).max().orElse(0))
                .groupRounds(groupRounds)
                .groupMatches(groupMatches)
                .groupRoundsConfigured(groupRoundsCfg > 0)
                .knockoutRounds(koRounds)
                .knockoutMatches(koMatches)
                .finalMatches(finalMatches)
                .byeCount(byeCount)
                .totalMatches(groupMatches + koMatches + finalMatches)
                .build();
    }

    /** 创建人展示名 */
    private String creatorName(User creator, Long creatorId) {
        if (creator == null) {
            return "球友#" + creatorId;
        }
        if (creator.getNickname() != null && !creator.getNickname().isBlank()) {
            return creator.getNickname().trim();
        }
        if (creator.getName() != null && !creator.getName().isBlank()) {
            return creator.getName().trim();
        }
        return "球友#" + creatorId;
    }

    /**
     * 管理端参赛名单：逐个参赛单位的明细（含队名、组别、战绩、名次）。
     *
     * <p>管理端要「移出参赛者」必须先拿到参赛单位 id 与队名，
     * 这份数据在赛事广场视图里刻意不暴露。
     */
    public List<TournamentEntryItem> entries(Long id, Long operatorId) {
        requireAdmin(operatorId);
        Tournament t = requireTournament(id);
        List<TournamentEntry> entries = loadEntries(id);
        Map<Long, User> users = loadUsers(entries);
        List<Integer> formats = parseFormats(t.getFormatList());

        List<TournamentEntryItem> out = new ArrayList<>(entries.size());
        for (TournamentEntry e : entries) {
            out.add(TournamentEntryItem.builder()
                    .entryId(e.getId())
                    .tournamentId(t.getId())
                    .format(nz(e.getFormat()))
                    .formatName(formatName(nz(e.getFormat())))
                    .name(teamName(e, users))
                    .members(membersOf(e, users))
                    .contact(e.getContact())
                    .seedNo(e.getSeedNo())
                    .groupNo(e.getGroupNo())
                    .groupRank(e.getGroupRank())
                    .groupWin(nz(e.getGroupWin()))
                    .groupLoss(nz(e.getGroupLoss()))
                    .groupPointDiff(nz(e.getGroupPointDiff()))
                    .advance(nz(e.getAdvance()) == 1)
                    .status(nz(e.getStatus()))
                    .statusText(ENTRY_STATUS_NAMES.getOrDefault(nz(e.getStatus()), "未知"))
                    .eliminatedStage(e.getEliminatedStage())
                    .finalRank(e.getFinalRank())
                    .createTime(e.getCreateTime())
                    .build());
        }
        // 组别按开设顺序，组内按抽签号
        out.sort(Comparator
                .comparingInt((TournamentEntryItem i) -> {
                    int idx = formats.indexOf(nz(i.getFormat()));
                    return idx < 0 ? Integer.MAX_VALUE : idx;
                })
                .thenComparing(i -> i.getSeedNo() == null ? Integer.MAX_VALUE : i.getSeedNo())
                .thenComparing(TournamentEntryItem::getEntryId));
        return out;
    }

    /**
     * 修改赛事（管理员 / 创建人）。
     *
     * <p>能改什么由赛制阶段决定：报名中可全改；已抽签后组别与赛制配置锁定，
     * 只能改标题 / 地点 / 日期 / 球场 / 说明；开打之后一律锁定。
     */
    @Transactional
    public Map<String, Object> update(Long id, TournamentUpdateRequest req, Long operatorId) {
        Tournament t = requireTournament(id);
        requireOwnerOrAdmin(t, operatorId);
        int status = nz(t.getStatus());
        if (status == Constants.TOURNAMENT_STATUS_RUNNING
                || status == Constants.TOURNAMENT_STATUS_FINISHED
                || status == Constants.TOURNAMENT_STATUS_CANCELLED) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "赛事已开打，不允许再修改配置");
        }
        boolean drawn = status == Constants.TOURNAMENT_STATUS_DRAWN;

        int groupSize = req.getGroupSize() == null ? nz(t.getGroupSize()) : req.getGroupSize();
        int advance = req.getAdvancePerGroup() == null
                ? nz(t.getAdvancePerGroup()) : req.getAdvancePerGroup();
        if (advance >= groupSize) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "每组晋级名额必须小于每组 " + groupSize + " 队，否则小组赛没有意义");
        }
        int maxEntries = req.getMaxEntries() == null ? nz(t.getMaxEntries()) : req.getMaxEntries();
        int groupRounds = req.getGroupRounds() == null ? nz(t.getGroupRounds()) : req.getGroupRounds();
        int knockoutRounds = req.getKnockoutRounds() == null
                ? nz(t.getKnockoutRounds()) : req.getKnockoutRounds();

        List<Integer> formats = drawn
                ? parseFormats(t.getFormatList())
                : normalizeFormats(req.getFormats());

        if (t.getTitle() == null || t.getTitle().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "赛事名称不能为空");
        }
        t.setTitle(req.getTitle().trim());
        t.setLocation(req.getLocation() == null ? null : req.getLocation().trim());
        t.setCourtId(req.getCourtId());
        t.setPlayDate(req.getPlayDate());
        t.setRemark(req.getRemark() == null ? null : req.getRemark().trim());

        if (!drawn) {
            // 未抽签才允许动组别与赛制：逐组检查新上限是否低于已报名队数
            List<TournamentEntry> current = loadEntries(id);
            for (int format : formats) {
                long inFormat = current.stream()
                        .filter(e -> nz(e.getFormat()) == format).count();
                if (inFormat > maxEntries) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            formatName(format) + "组已报名 " + inFormat
                                    + " 队，超过新上限 " + maxEntries + " 队");
                }
            }
            t.setFormatList(formats.stream().map(String::valueOf).collect(Collectors.joining(",")));
            t.setGroupSize(groupSize);
            t.setAdvancePerGroup(advance);
            t.setMaxEntries(maxEntries);
            t.setGroupRounds(groupRounds);
            t.setKnockoutRounds(knockoutRounds);
        }

        tournamentMapper.updateById(t);
        log.info("修改赛事: id={}, title={}, 赛制配置{}",
                id, t.getTitle(), drawn ? "锁定(已抽签)" : "已更新");
        return Map.of("id", id, "title", t.getTitle(),
                "configLocked", drawn,
                "formats", parseFormats(t.getFormatList()),
                "groupSize", nz(t.getGroupSize()),
                "advancePerGroup", nz(t.getAdvancePerGroup()),
                "maxEntries", nz(t.getMaxEntries()),
                "groupRounds", nz(t.getGroupRounds()),
                "knockoutRounds", nz(t.getKnockoutRounds()));
    }

    /* ==================== 模拟赛程 ==================== */

    /** 模拟过程中的一支虚拟队伍（全程只在内存里，不落库） */
    private static final class SimTeam {
        Long id;
        String name;
        String groupNo;
        int win;
        int loss;
        int pointDiff;
        int rank;
        boolean advance;
        int finalRank;
    }

    /**
     * 模拟赛程：把一场赛「预演」一遍，只看结构，不动数据。
     *
     * <p>分组、轮次、配对规则全部复用 {@link TournamentEngine} 与真实抽签（{@link #draw}），
     * 赛果则随机生成，所以模拟结果<b>每次都不一样</b>——
     * 它回答的是「这场赛要打几轮、一共几场、签表长什么样」，而不是「谁会赢」。
     *
     * <p>全程不写库，tournament / entry / group / match 四张表零改动。
     */
    public TournamentBracketResponse simulate(TournamentSimulateRequest req, Long operatorId) {
        requireAdmin(operatorId);

        Tournament t = req.getTournamentId() == null ? null : requireTournament(req.getTournamentId());

        int groupSize = req.getGroupSize() != null ? req.getGroupSize()
                : (t != null ? nz(t.getGroupSize()) : 4);
        int advance = req.getAdvancePerGroup() != null ? req.getAdvancePerGroup()
                : (t != null ? nz(t.getAdvancePerGroup()) : 1);
        int groupRounds = req.getGroupRounds() != null ? req.getGroupRounds()
                : (t != null ? nz(t.getGroupRounds()) : 0);
        int knockoutRounds = req.getKnockoutRounds() != null ? req.getKnockoutRounds()
                : (t != null ? nz(t.getKnockoutRounds()) : 0);
        if (advance >= groupSize) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "每组晋级名额必须小于每组 " + groupSize + " 队，否则小组赛没有意义");
        }

        List<Integer> formats;
        if (req.getFormats() != null && !req.getFormats().isEmpty()) {
            formats = normalizeFormats(req.getFormats());
        } else if (t != null) {
            formats = parseFormats(t.getFormatList());
        } else {
            formats = List.of(Constants.FORMAT_SINGLES, Constants.FORMAT_MEN_DOUBLES,
                    Constants.FORMAT_WOMEN_DOUBLES, Constants.FORMAT_MIXED_DOUBLES);
        }

        // 真实报名数（按组别），传了 entryCount 就用虚拟队数
        Map<Integer, Integer> realCounts = new HashMap<>();
        if (t != null) {
            Map<Integer, Long> counted = loadEntries(t.getId()).stream()
                    .collect(Collectors.groupingBy(e -> nz(e.getFormat()), Collectors.counting()));
            counted.forEach((k, v) -> realCounts.put(k, v.intValue()));
        }

        List<TournamentBracketResponse.FormatBoard> boards = new ArrayList<>();
        for (int format : formats) {
            int teamCount = req.getEntryCount() != null ? req.getEntryCount()
                    : realCounts.getOrDefault(format, 0);
            if (teamCount < 2) {
                continue;
            }
            boards.add(simulateFormat(format, teamCount, groupSize, advance, groupRounds, knockoutRounds));
        }
        if (boards.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR,
                    "没有可模拟的组别：至少要有一个组别凑够 2 队");
        }

        return TournamentBracketResponse.builder()
                .id(t == null ? null : t.getId())
                .title(t == null ? "模拟赛程" : t.getTitle() + "（模拟）")
                .status(t == null ? Constants.TOURNAMENT_STATUS_OPEN : nz(t.getStatus()))
                .statusText(t == null ? "模拟预览" : statusText(t.getStatus()))
                .formats(boards)
                .build();
    }

    /**
     * 模拟单个组别的完整赛程：小组赛 → 淘汰赛 → 季军赛 / 决赛 → 最终名次。
     *
     * <p>配对规则与真实赛制严格一致：
     * <ul>
     *   <li>小组赛：{@link TournamentEngine#roundRobin} 的轮转法，组内单循环；</li>
     *   <li>淘汰赛首轮：标准种子配对（1 vs N、2 vs N-1……），保证同组两队被拆到两端；</li>
     *   <li>淘汰赛后续轮：本轮胜者随机配对（{@link TournamentEngine#shufflePairs}）；</li>
     *   <li>四强打满：生成季军赛 + 决赛。</li>
     * </ul>
     */
    private TournamentBracketResponse.FormatBoard simulateFormat(int format, int teamCount,
                                                                int groupSize, int advance,
                                                                int groupRounds, int knockoutRounds) {
        // 1) 虚拟队伍：id 取负数，和真实 tournament_entry.id 彻底区分开
        Map<Long, SimTeam> teams = new LinkedHashMap<>();
        List<Long> teamIds = new ArrayList<>();
        for (int i = 1; i <= teamCount; i++) {
            long id = -i;
            SimTeam st = new SimTeam();
            st.id = id;
            st.name = "模拟队" + i;
            teams.put(id, st);
            teamIds.add(id);
        }

        // 2) 随机抽签分组（与真实 draw 同一套均摊逻辑）
        List<List<Long>> groups = engine.drawGroups(teamIds, groupSize);
        int groupCount = groups.size();
        int knockoutSize = engine.knockoutBracketSize(groupCount * advance, knockoutRounds);

        // 3) 小组赛：组内单循环 + 随机赛果
        List<TournamentBracketResponse.GroupBoard> groupBoards = new ArrayList<>();
        List<Long> advancedIds = new ArrayList<>();
        long matchSeq = 0;

        for (int gi = 0; gi < groups.size(); gi++) {
            List<Long> members = groups.get(gi);
            String groupNo = engine.groupNo(gi + 1);
            for (Long id : members) {
                teams.get(id).groupNo = groupNo;
            }

            List<List<TournamentEngine.Pair>> schedule = engine.roundRobin(members, groupRounds);
            List<TournamentMatchItem> matches = new ArrayList<>();
            for (int r = 0; r < schedule.size(); r++) {
                List<TournamentEngine.Pair> pairs = schedule.get(r);
                for (int k = 0; k < pairs.size(); k++) {
                    TournamentEngine.Pair pair = pairs.get(k);
                    SimTeam a = teams.get(pair.a());
                    SimTeam b = teams.get(pair.b());
                    boolean aWon = ThreadLocalRandom.current().nextBoolean();
                    int scoreA = 21;
                    int scoreB = ThreadLocalRandom.current().nextInt(0, 20);
                    int winner = aWon ? Constants.WINNER_A : Constants.WINNER_B;

                    a.win++;
                    b.loss++;
                    int delta = Math.abs(scoreA - scoreB);
                    a.pointDiff += delta;
                    b.pointDiff -= delta;

                    matches.add(simMatch(-(++matchSeq), Constants.TOURNAMENT_STAGE_GROUP,
                            r + 1, members.size(), "第 " + (r + 1) + " 轮", groupNo, k + 1, k + 1,
                            a, b, winner, scoreA, scoreB));
                }
            }

            // 组内排名 + 晋级标记（口径与 finishGroup 一致：排名可并列，晋级严格按名额取）
            List<TournamentEngine.GroupStanding> standings = members.stream()
                    .map(id -> new TournamentEngine.GroupStanding(id,
                            teams.get(id).win, teams.get(id).loss, teams.get(id).pointDiff))
                    .toList();
            List<TournamentEngine.GroupRanked> ranked = engine.rankGroup(standings);
            for (TournamentEngine.GroupRanked r : ranked) {
                SimTeam st = teams.get(r.entryId());
                st.rank = r.rank();
                st.advance = false;
            }
            // 按（名次 → id）取前 advance 名晋级，避免并列导致超编
            List<SimTeam> byRank = ranked.stream()
                    .map(r -> teams.get(r.entryId()))
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt((SimTeam t) -> t.rank).thenComparing(t -> t.id))
                    .toList();
            for (int i = 0; i < Math.min(advance, byRank.size()); i++) {
                byRank.get(i).advance = true;
                advancedIds.add(byRank.get(i).id);
            }

            groupBoards.add(TournamentBracketResponse.GroupBoard.builder()
                    .groupId(-(gi + 1L))
                    .groupNo(groupNo)
                    .entryCount(members.size())
                    .status(Constants.TOURNAMENT_GROUP_DONE)
                    .standings(ranked.stream().map(r -> simStanding(teams.get(r.entryId()))).toList())
                    .matches(matches)
                    .build());
        }

        // 4) 晋级队排序（小组名次 → 组名 → id），与 generateKnockoutFirstRound 一致
        List<Long> advancedSorted = advancedIds.stream()
                .sorted(Comparator.comparingInt((Long id) -> teams.get(id).rank)
                        .thenComparing(id -> teams.get(id).groupNo)
                        .thenComparing(id -> id))
                .toList();

        // 5) 淘汰赛：首轮标准种子配对，后续轮胜者随机配对
        List<TournamentBracketResponse.RoundBoard> rounds = new ArrayList<>();
        Map<Long, Integer> eliminatedRank = new HashMap<>();
        List<Long> current = advancedSorted;
        List<TournamentEngine.Pair> currentPairs = List.of();
        int roundSize = knockoutSize;
        int roundNo = 1;
        List<Long> finalWinners = List.of();
        List<Long> finalLosers = List.of();

        while (true) {
            // 本轮配对：首轮用标准种子签位（含轮空），后续轮用上一轮胜者随机配对
            List<TournamentEngine.Pair> pairs;
            if (roundNo == 1) {
                pairs = engine.seedPairs(current, roundSize);
            } else {
                pairs = currentPairs;
            }

            List<TournamentMatchItem> matches = new ArrayList<>();
            List<Long> roundWinners = new ArrayList<>();
            List<Long> roundLosers = new ArrayList<>();
            for (int k = 0; k < pairs.size(); k++) {
                TournamentEngine.Pair pair = pairs.get(k);
                SimTeam a = pair.a() == null ? null : teams.get(pair.a());
                SimTeam b = pair.b() == null ? null : teams.get(pair.b());
                boolean aWon;
                Integer scoreA;
                Integer scoreB;
                if (a == null) {
                    // 签位空在左边：B 队轮空晋级
                    aWon = false;
                    scoreA = null;
                    scoreB = null;
                } else if (b == null) {
                    // 签位空在右边：A 队轮空晋级
                    aWon = true;
                    scoreA = null;
                    scoreB = null;
                } else {
                    aWon = ThreadLocalRandom.current().nextBoolean();
                    scoreA = 21;
                    scoreB = ThreadLocalRandom.current().nextInt(0, 20);
                }
                int winner = aWon ? Constants.WINNER_A : Constants.WINNER_B;

                if (aWon) {
                    roundWinners.add(pair.a());
                    if (pair.b() != null) {
                        roundLosers.add(pair.b());
                    }
                } else {
                    roundWinners.add(pair.b());
                    if (pair.a() != null) {
                        roundLosers.add(pair.a());
                    }
                }

                matches.add(simMatch(-(++matchSeq), Constants.TOURNAMENT_STAGE_KNOCKOUT,
                        roundNo, roundSize, knockoutRoundName(roundSize), null, k + 1, k + 1,
                        a, b, winner, scoreA, scoreB));
            }

            rounds.add(TournamentBracketResponse.RoundBoard.builder()
                    .roundNo(roundNo)
                    .roundSize(roundSize)
                    .roundName(knockoutRoundName(roundSize))
                    .finished(true)
                    .matches(matches)
                    .build());

            if (roundSize > 4) {
                // 本轮败者按「止步 N 强」记名次（与 applyKnockoutScore 一致）
                for (Long loserId : roundLosers) {
                    eliminatedRank.put(loserId, roundSize / 2 + 1);
                }
                // 下一轮：本轮胜者随机配对
                currentPairs = engine.shufflePairs(roundWinners);
                roundSize = roundWinners.size();
                roundNo++;
                continue;
            }

            // 四强打满：收官战（季军赛 + 决赛）
            finalWinners = roundWinners;
            finalLosers = roundLosers;
            break;
        }

        // 6) 季军赛：四强负者争季军
        SimTeam bronzeWinner = null;
        SimTeam bronzeLoser = null;
        if (finalLosers.size() >= 2) {
            SimTeam a = teams.get(finalLosers.get(0));
            SimTeam b = teams.get(finalLosers.get(1));
            boolean aWon = ThreadLocalRandom.current().nextBoolean();
            int scoreA = 21;
            int scoreB = ThreadLocalRandom.current().nextInt(0, 20);
            bronzeWinner = aWon ? a : b;
            bronzeLoser = aWon ? b : a;
            rounds.add(TournamentBracketResponse.RoundBoard.builder()
                    .roundNo(0)
                    .roundSize(0)
                    .roundName("季军赛")
                    .finished(true)
                    .matches(List.of(simMatch(-(++matchSeq), Constants.TOURNAMENT_STAGE_BRONZE,
                            0, 0, "季军赛", null, 1, 1, a, b,
                            aWon ? Constants.WINNER_A : Constants.WINNER_B, scoreA, scoreB)))
                    .build());
        }

        // 7) 决赛：四强胜者争冠
        SimTeam champion = null;
        SimTeam runnerUp = null;
        if (finalWinners.size() >= 2) {
            SimTeam a = teams.get(finalWinners.get(0));
            SimTeam b = teams.get(finalWinners.get(1));
            boolean aWon = ThreadLocalRandom.current().nextBoolean();
            int scoreA = 21;
            int scoreB = ThreadLocalRandom.current().nextInt(0, 20);
            champion = aWon ? a : b;
            runnerUp = aWon ? b : a;
            rounds.add(TournamentBracketResponse.RoundBoard.builder()
                    .roundNo(0)
                    .roundSize(0)
                    .roundName("决赛")
                    .finished(true)
                    .matches(List.of(simMatch(-(++matchSeq), Constants.TOURNAMENT_STAGE_FINAL,
                            0, 0, "决赛", null, 1, 1, a, b,
                            aWon ? Constants.WINNER_A : Constants.WINNER_B, scoreA, scoreB)))
                    .build());
        }

        // 8) 最终名次
        if (champion != null) {
            champion.finalRank = 1;
        }
        if (runnerUp != null) {
            runnerUp.finalRank = 2;
        }
        if (bronzeWinner != null) {
            bronzeWinner.finalRank = 3;
        }
        if (bronzeLoser != null) {
            bronzeLoser.finalRank = 4;
        }
        eliminatedRank.forEach((id, rank) -> teams.get(id).finalRank = rank);

        List<TournamentBracketResponse.RankItem> ranking = teams.values().stream()
                .filter(t -> t.finalRank > 0)
                .sorted(Comparator.comparingInt(t -> t.finalRank))
                .map(t -> simRankItem(t, eliminatedRank.containsKey(t.id)
                        ? simEliminatedText(t.finalRank) : null))
                .toList();

        // 补上轮次之间的父子关系，前端据此渲染树形对阵图
        fillChildMatchIds(rounds);

        return TournamentBracketResponse.FormatBoard.builder()
                .format(format)
                .formatName(formatName(format))
                .groups(groupBoards)
                .knockouts(rounds)
                .ranking(ranking)
                .alive(List.of())
                .build();
    }

    /** 淘汰赛轮次中文名（32 强 / 十六强 …） */
    private String knockoutRoundName(int roundSize) {
        return KNOCKOUT_ROUND_NAMES.getOrDefault(roundSize, roundSize + " 强");
    }

    /** 模拟队伍的名片 */
    private TournamentMatchItem.MemberBrief simMember(SimTeam t) {
        return TournamentMatchItem.MemberBrief.builder()
                .userId(t.id)
                .name(t.name)
                .avatar(null)
                .gender(0)
                .build();
    }

    private TournamentMatchItem.TeamItem simTeamItem(SimTeam t) {
        return TournamentMatchItem.TeamItem.builder()
                .entryId(t.id)
                .name(t.name)
                .members(List.of(simMember(t)))
                .groupNo(t.groupNo)
                .advance(t.advance)
                .finalRank(t.finalRank > 0 ? t.finalRank : null)
                .build();
    }

    private TournamentMatchItem simMatch(long id, int stage, int roundNo, int roundSize, String roundName,
                                         String groupNo, int matchNo, int court,
                                         SimTeam a, SimTeam b, int winner, Integer scoreA, Integer scoreB) {
        return TournamentMatchItem.builder()
                .id(id)
                .stage(stage)
                .roundNo(roundNo)
                .roundSize(roundSize)
                .roundName(roundName)
                .groupNo(groupNo)
                .matchNo(matchNo)
                .court(court)
                .teamA(a == null ? null : simTeamItem(a))
                .teamB(b == null ? null : simTeamItem(b))
                .winner(winner)
                .scoreA(scoreA)
                .scoreB(scoreB)
                .refereeName("系统模拟")
                .finished(true)
                .involved(false)
                .build();
    }

    private TournamentBracketResponse.StandingItem simStanding(SimTeam t) {
        return TournamentBracketResponse.StandingItem.builder()
                .entryId(t.id)
                .name(t.name)
                .members(List.of(simMember(t)))
                .rank(t.rank)
                .win(t.win)
                .loss(t.loss)
                .pointDiff(t.pointDiff)
                .advance(t.advance)
                .build();
    }

    private TournamentBracketResponse.RankItem simRankItem(SimTeam t, String eliminatedText) {
        return TournamentBracketResponse.RankItem.builder()
                .rank(t.finalRank)
                .entryId(t.id)
                .name(t.name)
                .members(List.of(simMember(t)))
                .groupNo(t.groupNo)
                .eliminatedText(eliminatedText)
                .build();
    }

    /** 淘汰赛止步说明：由名次反推轮次（17 → 三十二强、9 → 十六强、5 → 八强） */
    private String simEliminatedText(int rank) {
        if (rank <= 3) {
            return null;
        }
        int size = (rank - 1) * 2;
        return "止步" + knockoutRoundName(size);
    }

    /** 赛事详情：基本信息 + 各组别报名情况 + 我的参赛单位 + 领奖台 */
    public TournamentDetailResponse detail(Long id, Long userId) {
        Tournament t = requireTournament(id);
        List<TournamentEntry> entries = loadEntries(id);
        Map<Long, User> users = loadUsers(entries);
        boolean admin = canManageTournament(userId);
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
            // 补上轮次之间的父子关系，前端据此渲染树形对阵图
            fillChildMatchIds(rounds);

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

        // 兜底推进：轮空场不需要人工录分，可能有「整轮已完但下一轮没生成」的情况
        for (int format : formats) {
            advanceKnockoutRounds(t, format);
        }
        // 推进可能补出新轮次，所以比赛数据要在推进之后再取
        List<TournamentMatch> allMatches = loadMatches(id);

        List<TournamentMatchesResponse.FormatMatches> out = new ArrayList<>();
        for (int format : formats) {
            List<TournamentMatchItem> items = allMatches.stream()
                    .filter(m -> Objects.equals(m.getFormat(), format))
                    // 轮空场次（只有一支队）不需要录入比分，也不该出现在录入列表里
                    .filter(m -> m.getTeamA() != null && m.getTeamB() != null)
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
                .admin(canManageTournament(userId))
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
        t.setGroupRounds(req.getGroupRounds() == null ? 0 : req.getGroupRounds());
        t.setKnockoutRounds(req.getKnockoutRounds() == null ? 0 : req.getKnockoutRounds());
        t.setStatus(Constants.TOURNAMENT_STATUS_OPEN);
        t.setCreatorId(creatorId);
        tournamentMapper.insert(t);

        log.info("创建赛事: id={}, title={}, 组别={}, 每组{}队×{}名额, 小组赛{}轮, 淘汰赛{}轮",
                t.getId(), t.getTitle(), t.getFormatList(), groupSize, advance,
                t.getGroupRounds(), t.getKnockoutRounds());
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
        // 未抽签：队长可以自行撤回；已抽签：只有管理员 / 创建人能移出参赛名单。
        // 管理员 / 创建人在任何阶段都能移出——报名中也要能处理「重复报名 / 不符合组别资格」的队伍。
        if (canManageTournament(userId) || Objects.equals(t.getCreatorId(), userId)) {
            // 管理员操作，直接放行
        } else if (Objects.equals(t.getStatus(), Constants.TOURNAMENT_STATUS_OPEN)
                && Objects.equals(e.getUserId(), userId)) {
            // 报名中本人自行撤回
        } else {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "只能撤回自己提交的报名，或由管理员移出参赛名单");
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
        int groupRoundsCfg = nz(t.getGroupRounds());
        int knockoutRoundsCfg = nz(t.getKnockoutRounds());
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
            if (knockoutRoundsCfg <= 0) {
                // 未指定淘汰赛轮数：沿用严格校验——晋级人数必须恰好是 2 的幂才打得了完整签表
                try {
                    engine.knockoutSize(advanceTotal);
                } catch (BizException e) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            formatName(format) + "组共 " + inFormat.size() + " 队，按每组 " + groupSize
                                    + " 队 × 每组 " + advancePerGroup + " 名 = 晋级 " + advanceTotal + " 人。"
                                    + e.getMessage());
                }
            } else {
                // 指定了淘汰赛轮数：规模自动放大到能容纳全部晋级队，放不下的部分首轮轮空
                int size = engine.knockoutBracketSize(advanceTotal, knockoutRoundsCfg);
                if (size > Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            formatName(format) + "组晋级 " + advanceTotal + " 队，按指定淘汰赛轮数需要 "
                                    + size + " 强签表，超过上限 " + Constants.TOURNAMENT_MAX_KNOCKOUT_SIZE);
                }
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

            // 3) 组内赛程：按赛事配置的轮数生成（0 = 打满单循环，>0 = 指定轮数，可多循环）
            for (int i = 0; i < drawn.size(); i++) {
                TournamentGroup g = findGroup(tournamentId, format, engine.groupNo(i + 1));
                int teamCount = drawn.get(i).size();
                int fullRounds = engine.groupRounds(teamCount);
                if (groupRoundsCfg > 0 && groupRoundsCfg > fullRounds * 3) {
                    throw new BizException(ErrorCode.PARAM_ERROR,
                            formatName(format) + "组 " + teamCount + " 队单循环满轮 " + fullRounds
                                    + " 轮，小组赛轮数不能超过 " + (fullRounds * 3) + " 轮");
                }
                List<List<TournamentEngine.Pair>> schedule = engine.roundRobin(drawn.get(i), groupRoundsCfg);
                for (int r = 0; r < schedule.size(); r++) {
                    List<TournamentEngine.Pair> pairs = schedule.get(r);
                    for (int k = 0; k < pairs.size(); k++) {
                        TournamentMatch m = newMatch(tournamentId, format, Constants.TOURNAMENT_STAGE_GROUP);
                        m.setGroupId(g.getId());
                        m.setRoundNo(r + 1);
                        m.setRoundSize(teamCount);
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
            one.put("groupRounds", groupRoundsCfg > 0
                    ? groupRoundsCfg
                    : engine.groupRounds(drawn.get(0).size()));
            one.put("knockoutSize", engine.knockoutBracketSize(drawn.size() * advancePerGroup, knockoutRoundsCfg));
            formatSummary.add(one);
        }

        if (formatSummary.isEmpty()) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "没有任何组别凑够 2 队，无法抽签");
        }

        t.setStatus(Constants.TOURNAMENT_STATUS_DRAWN);
        tournamentMapper.updateById(t);

        // 兜底推进：若某组别的淘汰赛首轮全是轮空（没人能触发录分），这里把下一轮补上
        for (int format : formats) {
            advanceKnockoutRounds(t, format);
        }

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
        if (m.getTeamA() == null || m.getTeamB() == null) {
            throw new BizException(ErrorCode.TOURNAMENT_STATUS_ERROR, "轮空场次没有比分，无需撤销");
        }
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
            e.setAdvance(0);
            entryMapper.updateById(e);
        }

        // 晋级必须严格按名额取：排名可能并列（名次相同），
        // 若直接用「rank <= 名额」判定，并列的队会一起晋级，晋级人数就超编了——
        // 而「晋级人数 = 组数 × 每组名额」是后面淘汰赛签表规模的计算基础，超编会让签表全错。
        // 所以这里按（名次 → id）排序取前 N 个，id 兜底保证结果确定可复现。
        List<TournamentEntry> byRank = ranked.stream()
                .map(r -> getEntry(r.entryId()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt((TournamentEntry e) -> nz(e.getGroupRank()))
                        .thenComparing(TournamentEntry::getId))
                .toList();
        for (int i = 0; i < Math.min(advancePerGroup, byRank.size()); i++) {
            TournamentEntry e = byRank.get(i);
            e.setAdvance(1);
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
     *
     * <p>规模由 {@link TournamentEngine#knockoutBracketSize} 决定：
     * 未配置淘汰赛轮数时严格要求晋级人数是 2 的幂；配置了轮数则规模自动放大到能容纳晋级队，
     * 放不下的位置留作<b>轮空</b>——轮空场次直接判定该队晋级，不需要录入比分。
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
        int size = engine.knockoutBracketSize(advanced.size(), nz(t.getKnockoutRounds()));
        List<TournamentEngine.Pair> pairs = engine.seedPairs(
                advanced.stream().map(TournamentEntry::getId).toList(), size);

        int byeCount = 0;
        for (int i = 0; i < pairs.size(); i++) {
            TournamentEngine.Pair pair = pairs.get(i);
            TournamentMatch m = newMatch(t.getId(), format, Constants.TOURNAMENT_STAGE_KNOCKOUT);
            m.setRoundNo(1);
            m.setRoundSize(size);
            m.setMatchNo(i + 1);
            m.setCourt(i + 1);
            m.setTeamA(pair.a());
            m.setTeamB(pair.b());
            if (pair.a() == null) {
                // 签位空在左边：这支队轮空，首轮直接晋级
                byeCount++;
                m.setWinner(Constants.WINNER_B);
            } else if (pair.b() == null) {
                // 签位空在右边
                byeCount++;
                m.setWinner(Constants.WINNER_A);
            }
            matchMapper.insert(m);
        }
        for (TournamentEntry e : advanced) {
            e.setStatus(Constants.TOURNAMENT_ENTRY_ALIVE);
            entryMapper.updateById(e);
        }
        t.setStatus(Constants.TOURNAMENT_STATUS_RUNNING);
        log.info("生成淘汰赛首轮: tournament={}, {} {}强（{} 队晋级，{} 支轮空）",
                t.getId(), formatName(format), size, advanced.size(), byeCount);
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
            generateNextKnockoutRound(t, format, roundNo, winners);
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

    /**
     * 用本轮胜者随机配对，生成淘汰赛下一轮的对阵。
     */
    private void generateNextKnockoutRound(Tournament t, int format, int roundNo, List<Long> winners) {
        if (winners.size() < 2) {
            return;
        }
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
    }

    /**
     * 淘汰赛轮次兜底推进。
     *
     * <p>正常情况下下一轮由 {@link #applyKnockoutScore} 在「本轮录完最后一场」时顺带生成。
     * 但<b>轮空场次不需要人工录分</b>（结果已自动判定），于是出现两种卡死：
     * <ul>
     *   <li>某轮全是轮空 → 没人调用 score() → 下一轮永远生成不出来；</li>
     *   <li>某轮比赛还没打 → 本来就该停住等裁判录分。</li>
     * </ul>
     * 所以这里在「打开赛程录入页」时主动检查：若最后一轮已经全部有结果
     * （含轮空场自动算完成）但下一轮还没生成，就把它补上。
     */
    private void advanceKnockoutRounds(Tournament t, int format) {
        for (int guard = 0; guard < 16; guard++) {
            List<TournamentMatch> koMatches = loadMatches(t.getId()).stream()
                    .filter(m -> nz(m.getFormat()) == format
                            && nz(m.getStage()) == Constants.TOURNAMENT_STAGE_KNOCKOUT)
                    .toList();
            if (koMatches.isEmpty()) {
                return;
            }
            int maxRound = koMatches.stream().mapToInt(m -> nz(m.getRoundNo())).max().orElse(0);
            List<TournamentMatch> round = koMatches.stream()
                    .filter(m -> nz(m.getRoundNo()) == maxRound)
                    .sorted(Comparator.comparing(TournamentMatch::getMatchNo))
                    .toList();
            boolean allDone = round.stream().allMatch(m -> m.getWinner() != null
                    && m.getWinner() != Constants.WINNER_NONE);
            if (!allDone) {
                // 本轮还有比赛没打：等裁判录分，正常停在这里
                return;
            }
            int roundSize = nz(round.get(0).getRoundSize());
            if (roundSize <= 4) {
                // 四强轮：收官战由 applyKnockoutScore 在录分时生成
                return;
            }
            List<Long> winners = round.stream()
                    .map(x -> x.getWinner() == Constants.WINNER_A ? x.getTeamA() : x.getTeamB())
                    .filter(Objects::nonNull).toList();
            generateNextKnockoutRound(t, format, maxRound, winners);
        }
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

    /** 赛事管理判定：持 tournament:manage 权限码（干事以上） */
    private boolean canManageTournament(Long userId) {
        return userId != null
                && permissionService.hasPermission(userId, PermissionService.P_TOURNAMENT_MANAGE);
    }

    private void requireAdmin(Long userId) {
        if (!canManageTournament(userId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "仅干事以上角色可操作");
        }
    }

    /** 管理员或赛事创建人（创建人也是管理员才能建赛事，这里主要覆盖「非管理员但自己建的」的历史数据） */
    private void requireOwnerOrAdmin(Tournament t, Long userId) {
        if (canManageTournament(userId) || Objects.equals(t.getCreatorId(), userId)) {
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
        if (canManageTournament(operatorId) || Objects.equals(t.getCreatorId(), operatorId)) {
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

    /**
     * 给每场对阵补上「由上一轮哪几场产生」，前端据此渲染树形对阵图。
     *
     * <p>轮次之间是随机配对的（后续轮用胜者随机配对），没法从签表静态推出来，
     * 所以按「队伍 → 上一轮场次」反查：某支队伍出现在上一轮的哪一场，那一场就是它的来源。
     */
    private void fillChildMatchIds(List<TournamentBracketResponse.RoundBoard> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return;
        }
        // 淘汰赛各轮（roundNo >= 1）；收官战的 roundNo = 0
        List<TournamentBracketResponse.RoundBoard> koRounds = rounds.stream()
                .filter(r -> r.getRoundNo() > 0)
                .sorted(Comparator.comparingInt(TournamentBracketResponse.RoundBoard::getRoundNo))
                .toList();
        List<TournamentBracketResponse.RoundBoard> finals = rounds.stream()
                .filter(r -> r.getRoundNo() == 0)
                .toList();

        // 逐轮推进：第 r 轮的来源是第 r-1 轮
        for (int i = 1; i < koRounds.size(); i++) {
            Map<Long, Long> teamToMatch = teamMatchMap(koRounds.get(i - 1));
            for (TournamentMatchItem m : koRounds.get(i).getMatches()) {
                m.setChildMatchIds(childIds(m, teamToMatch));
            }
        }
        // 收官战（季军赛 / 决赛）都从淘汰赛最后一轮分叉出来
        if (!koRounds.isEmpty() && !finals.isEmpty()) {
            Map<Long, Long> teamToMatch = teamMatchMap(koRounds.get(koRounds.size() - 1));
            for (TournamentBracketResponse.RoundBoard f : finals) {
                for (TournamentMatchItem m : f.getMatches()) {
                    m.setChildMatchIds(childIds(m, teamToMatch));
                }
            }
        }
    }

    /** 某轮里「每支队伍出现在哪一场」的映射：entryId → matchId */
    private Map<Long, Long> teamMatchMap(TournamentBracketResponse.RoundBoard round) {
        Map<Long, Long> map = new HashMap<>();
        for (TournamentMatchItem m : round.getMatches()) {
            if (m.getTeamA() != null && m.getTeamA().getEntryId() != null) {
                map.putIfAbsent(m.getTeamA().getEntryId(), m.getId());
            }
            if (m.getTeamB() != null && m.getTeamB().getEntryId() != null) {
                map.putIfAbsent(m.getTeamB().getEntryId(), m.getId());
            }
        }
        return map;
    }

    /** 本场的两支队伍各自来自上一轮的哪一场 */
    private List<Long> childIds(TournamentMatchItem m, Map<Long, Long> teamToMatch) {
        List<Long> ids = new ArrayList<>();
        if (m.getTeamA() != null && m.getTeamA().getEntryId() != null) {
            Long pid = teamToMatch.get(m.getTeamA().getEntryId());
            if (pid != null && !ids.contains(pid)) {
                ids.add(pid);
            }
        }
        if (m.getTeamB() != null && m.getTeamB().getEntryId() != null) {
            Long pid = teamToMatch.get(m.getTeamB().getEntryId());
            if (pid != null && !ids.contains(pid)) {
                ids.add(pid);
            }
        }
        return ids;
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
        return MatchFormatUtil.formatName(format);
    }

    /** 每队人数：单打 1，其余 2 */
    private int teamSize(int format) {
        return MatchFormatUtil.teamSize(format);
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
                if (MatchFormatUtil.isStorableFormat(f)) {
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
            if (!MatchFormatUtil.isStorableFormat(f)) {
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