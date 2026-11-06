package com.yuyue.ai.service;

import com.yuyue.ai.kb.AiKnowledgeHit;
import com.yuyue.ai.tool.AiToolOutcome;
import com.yuyue.entity.AiMessage;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 提示词构造。
 * <p>
 * 三条原则：
 * <ol>
 *   <li><b>材料与问题分离</b>：规则、实时数据、对话历史全放进 system，用户当前问题单独作为 user，
 *       模型不容易把「历史里的旧问题」当成当前任务；</li>
 *   <li><b>知识库带编号</b>：模型用 [1] 标注来源，前端拿着同序号的 citations 渲染角标，可追溯；</li>
 *   <li><b>明确允许说不知道</b>：不写「尽力回答」，否则模型会为了完成任务编规则——
 *       社团规则里编错一条 K 值，比不回答更糟。</li>
 * </ol>
 */
@Component
public class AiPromptBuilder {

    /** 历史单条截断：长回答塞进提示词会让每轮成本线性上涨 */
    private static final int HISTORY_ITEM_MAX = 300;
    private static final int QUESTION_MAX = 1000;

    public String systemPrompt(List<AiKnowledgeHit> hits, AiToolOutcome outcome) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("你是「数科羽」校园羽毛球社团的智能助手。回答用中文，语气像社团里熟悉规则的老球友：")
                .append("准确、直接、不说客套话。\n\n");

        sb.append("【规则知识库】（规则类问题的唯一可信来源）\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("（本次没有检索到相关规则条目）\n");
        } else {
            int index = 1;
            for (AiKnowledgeHit hit : hits) {
                sb.append('[').append(index++).append("] 《")
                        .append(hit.chunk().displayTitle()).append("》\n")
                        .append(hit.chunk().content()).append("\n\n");
            }
        }

        sb.append("【实时数据】（来自系统数据库，只读，仅代表当前用户此刻的数据）\n");
        if (outcome != null && outcome.hasFacts()) {
            sb.append(outcome.facts()).append('\n');
        } else {
            sb.append("（本次没有查询数据库）\n");
        }

        sb.append("【回答要求】\n")
                .append("1. 规则问题只依据【规则知识库】作答，并在句末用 [1]、[2] 标注引用的条目编号；")
                .append("没有依据的规则不要讲。\n")
                .append("2. 数据问题只依据【实时数据】作答，不要猜测他人的积分、排名或球局。\n")
                .append("3. 材料里没有的信息，直接说「这条我不确定」或「没查到」，并建议去对应页面查看或联系管理员；")
                .append("禁止编造规则、数字、日期和接口。\n")
                .append("4. 回答控制在 200 字以内；需要列举时用「·」开头，不要使用 Markdown 标题或表格。\n")
                .append("5. 不要承诺替用户完成操作（报名、改分、删数据），只说明他应该去哪个页面做什么。\n")
                .append("6. 与羽毛球社团无关的问题，礼貌说明只能回答社团相关问题。\n");
        return sb.toString();
    }

    /**
     * 只带「最近若干条」历史：再多也不会显著提升短问答的质量，反而稀释当前问题的权重。
     */
    public String userPrompt(String question, List<AiMessage> history) {
        StringBuilder sb = new StringBuilder(256);
        if (history != null && !history.isEmpty()) {
            sb.append("【最近对话】（仅用于理解指代，不要重复其中的内容）\n");
            for (AiMessage message : history) {
                String role = AiMessage.ROLE_USER.equals(message.getRole()) ? "用户" : "助手";
                sb.append(role).append("：").append(truncate(message.getContent(), HISTORY_ITEM_MAX)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("【当前问题】\n").append(truncate(question, QUESTION_MAX));
        return sb.toString();
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String oneLine = text.strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "…";
    }
}
