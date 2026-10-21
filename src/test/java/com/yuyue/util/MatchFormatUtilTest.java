package com.yuyue.util;

import com.yuyue.common.Constants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对阵规则收敛测试：赛制文案、组队人数、赛制合法性、一队构成。
 *
 * <p>这些规则过去散落三四个类各写一份，本测试把「唯一的出口」钉住——
 * 以后谁想再加一份实现，至少会看到这里的期望值。
 */
class MatchFormatUtilTest {

    @Test
    @DisplayName("赛制文案：五个已知值各有名字，非法值与 null 一律「未知」")
    void formatName() {
        assertEquals("单打", MatchFormatUtil.formatName(Constants.FORMAT_SINGLES));
        assertEquals("男双", MatchFormatUtil.formatName(Constants.FORMAT_MEN_DOUBLES));
        assertEquals("女双", MatchFormatUtil.formatName(Constants.FORMAT_WOMEN_DOUBLES));
        assertEquals("混双", MatchFormatUtil.formatName(Constants.FORMAT_MIXED_DOUBLES));
        assertEquals("随机双打", MatchFormatUtil.formatName(Constants.FORMAT_RANDOM_DOUBLES));
        // 不再兜底成「双打」把非法值说成合法赛制
        assertEquals("未知", MatchFormatUtil.formatName(99));
        assertEquals("未知", MatchFormatUtil.formatName(null));
    }

    @Test
    @DisplayName("每队人数：单打 1 人，其余 2 人")
    void teamSize() {
        assertEquals(1, MatchFormatUtil.teamSize(Constants.FORMAT_SINGLES));
        assertEquals(2, MatchFormatUtil.teamSize(Constants.FORMAT_MEN_DOUBLES));
        assertEquals(2, MatchFormatUtil.teamSize(Constants.FORMAT_MIXED_DOUBLES));
    }

    @Test
    @DisplayName("可落库赛制：含四种真实组别，不含仅供试算的随机双打")
    void storableFormats() {
        assertTrue(MatchFormatUtil.isStorableFormat(Constants.FORMAT_SINGLES));
        assertTrue(MatchFormatUtil.isStorableFormat(Constants.FORMAT_MEN_DOUBLES));
        assertTrue(MatchFormatUtil.isStorableFormat(Constants.FORMAT_WOMEN_DOUBLES));
        assertTrue(MatchFormatUtil.isStorableFormat(Constants.FORMAT_MIXED_DOUBLES));
        assertFalse(MatchFormatUtil.isStorableFormat(Constants.FORMAT_RANDOM_DOUBLES));
        assertFalse(MatchFormatUtil.isStorableFormat(99));
        assertFalse(MatchFormatUtil.isStorableFormat(null));
    }

    @Test
    @DisplayName("一队构成：1 人单打 / 全男男双 / 全女女双 / 男女混双")
    void sideFormatName() {
        Map<Long, Integer> genders = Map.of(1L, 1, 2L, 1, 3L, 2, 4L, 2);
        assertEquals("单打", MatchFormatUtil.sideFormatName(List.of(1L), genders));
        assertEquals("男双", MatchFormatUtil.sideFormatName(List.of(1L, 2L), genders));
        assertEquals("女双", MatchFormatUtil.sideFormatName(List.of(3L, 4L), genders));
        assertEquals("混双", MatchFormatUtil.sideFormatName(List.of(1L, 3L), genders));
    }

    @Test
    @DisplayName("性别信息不全时返回 null，由调用方用整场 format 兜底")
    void sideFormatNameUnknownGender() {
        Map<Long, Integer> genders = new java.util.HashMap<>();
        genders.put(1L, 1);
        genders.put(2L, null);
        assertNull(MatchFormatUtil.sideFormatName(List.of(1L, 2L), genders));
        assertNull(MatchFormatUtil.sideFormatName(List.of(1L, 2L), null));
        assertNull(MatchFormatUtil.sideFormatName(List.of(), genders));
    }
}