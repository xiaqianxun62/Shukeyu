package com.yuyue.arch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块登记表：把「每个类属于哪个业务域」变成一张显式的表。
 *
 * <p>{@link ModuleBoundaryTest} 靠它判断「这个 Service 摸的 Mapper 是不是别的模块的」；
 * 将来真正分包时，它也是迁移的唯一依据。
 *
 * <p>新增 Service / Controller / Engine 时必须在这里登记一行，
 * 否则架构测试直接失败——这是防止「随手加个类，不知道该放哪个模块」的关键。
 */
public final class ModuleRegistry {

    public static final String GAME = "game";
    public static final String MATCH = "match";
    public static final String ROTATION = "rotation";
    public static final String TOURNAMENT = "tournament";
    public static final String ELO = "elo";
    public static final String USER = "user";
    public static final String OPS = "ops";

    public static final List<String> ALLOWED_MODULES =
            List.of(GAME, MATCH, ROTATION, TOURNAMENT, ELO, USER, OPS);

    /** 类简单名 → 模块 */
    public static final Map<String, String> MODULES = new LinkedHashMap<>();

    /** 全部纳入边界约束的类 */
    public static final List<Class<?>> SERVICES = List.of(
            cn("com.yuyue.service.CaptchaService"),
            cn("com.yuyue.service.EloRulesService"),
            cn("com.yuyue.service.EloSimulateService"),
            cn("com.yuyue.service.GameCleanupService"),
            cn("com.yuyue.service.GameService"),
            cn("com.yuyue.service.MatchGameWriter"),
            cn("com.yuyue.service.MatchService"),
            cn("com.yuyue.service.MatchSettleService"),
            cn("com.yuyue.service.RankingService"),
            cn("com.yuyue.service.RegistrationSnapshotService"),
            cn("com.yuyue.service.RotationService"),
            cn("com.yuyue.service.SettingsService"),
            cn("com.yuyue.service.TournamentService"),
            cn("com.yuyue.service.UserService"),
            cn("com.yuyue.service.VerificationService"),
            cn("com.yuyue.service.VisitLogService"),
            cn("com.yuyue.service.WeChatService"));

    public static final List<Class<?>> CONTROLLERS = List.of(
            cn("com.yuyue.controller.AuthController"),
            cn("com.yuyue.controller.CourtController"),
            cn("com.yuyue.controller.EloController"),
            cn("com.yuyue.controller.GameController"),
            cn("com.yuyue.controller.MatchController"),
            cn("com.yuyue.controller.MyHistoryController"),
            cn("com.yuyue.controller.RankingController"),
            cn("com.yuyue.controller.RotationController"),
            cn("com.yuyue.controller.SettingsController"),
            cn("com.yuyue.controller.TournamentController"),
            cn("com.yuyue.controller.UserController"),
            cn("com.yuyue.controller.VerificationController"),
            cn("com.yuyue.controller.VisitLogController"));

    /** 四个纯计算引擎：零 Spring 依赖，是将来拆服务的种子 */
    public static final List<Class<?>> ENGINES = List.of(
            cn("com.yuyue.engine.ArrangeEngine"),
            cn("com.yuyue.engine.EloCalculator"),
            cn("com.yuyue.engine.RotationEngine"),
            cn("com.yuyue.engine.TournamentEngine"));

    private static Class<?> cn(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("模块登记表引用了不存在的类: " + name, e);
        }
    }

    public static List<Class<?>> allComponents() {
        List<Class<?>> all = new ArrayList<>(SERVICES);
        all.addAll(CONTROLLERS);
        all.addAll(ENGINES);
        return all;
    }

    static {
        // 对局域：比赛、计分、结算、战绩
        reg(MATCH, "MatchService", "MatchSettleService", "MatchGameWriter",
                "MatchController", "MyHistoryController");
        // 轮排域
        reg(ROTATION, "RotationService", "RotationController", "RotationEngine");
        // 球局域：球局、报名
        reg(GAME, "GameService", "GameCleanupService", "RegistrationSnapshotService",
                "GameController", "ArrangeEngine");
        // 锦标赛域
        reg(TOURNAMENT, "TournamentService", "TournamentController", "TournamentEngine");
        // 积分域
        reg(ELO, "EloRulesService", "EloSimulateService", "EloController", "EloCalculator");
        // 用户域：登录、资料、榜单、认证
        reg(USER, "UserService", "RankingService", "WeChatService", "VerificationService",
                "CaptchaService", "AuthController", "UserController", "VerificationController",
                "RankingController");
        // 运营域：设置、访问日志、场地
        reg(OPS, "SettingsService", "VisitLogService",
                "SettingsController", "VisitLogController", "CourtController");
    }

    private static void reg(String module, String... simpleNames) {
        for (String name : simpleNames) {
            MODULES.put(name, module);
        }
    }

    /**
     * 已知越界点：Controller 直接注入了 Mapper，绕过了 Service 层。
     *
     * <p>收敛一处就从这里删一行。清单与实际完全一致才算通过——
     * 新增越界会红，收敛完忘了删也会红。
     */
    public static final Map<String, List<String>> KNOWN_CONTROLLER_MAPPER_BYPASS = Map.of(
            "CourtController", List.of("CourtMapper", "UserMapper"),
            "MyHistoryController",
            List.of("MatchGameMapper", "RatingHistoryMapper", "GameMapper", "UserMapper"),
            "SettingsController", List.of("UserMapper"),
            "UserController", List.of("UserMapper"),
            "VerificationController", List.of("UserMapper"),
            "VisitLogController", List.of("UserMapper"));

    private ModuleRegistry() {}
}