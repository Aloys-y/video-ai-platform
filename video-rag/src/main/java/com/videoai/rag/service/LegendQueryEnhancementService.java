package com.videoai.rag.service;

import com.videoai.infra.rag.config.RagProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将已确认的中文英雄名、玩家俗称和技能俗称解析为中文知识库规范实体。
 * 规则保持小而明确：只有先命中英雄别名，才解析该英雄名下的技能，避免把“烟”“扫描”等通用词误扩展。
 */
@Service
@RequiredArgsConstructor
public class LegendQueryEnhancementService {

    private static final List<LegendRule> RULES = List.of(
            rule("变幻", aliases("变幻")),
            rule("艾许", aliases("艾许", "艾什"), ability("弧形拘束器", "电弧绊索", "弧形拘束器")),
            rule("艾瑟儿", aliases("艾瑟儿", "艾瑟尔")),
            rule("弹道", aliases("弹道")),
            rule("班加罗尔", aliases("班加罗尔", "班加"), ability("烟雾发射器", "烟雾", "烟墙")),
            rule("寻血猎犬", aliases("寻血猎犬", "猎犬", "狗子"),
                    ability("众父之眼", "扫描", "上帝之眼", "众父之眼"),
                    ability("狩猎之兽", "狩猎野兽", "狩猎之兽")),
            rule("催化姬", aliases("催化姬")),
            rule("腐蚀", aliases("腐蚀"),
                    ability("诺克斯毒气陷阱", "毒气罐", "毒罐"),
                    ability("诺克斯毒气手雷", "毒气手雷")),
            rule("导线管", aliases("导线管")),
            rule("密客", aliases("密客"),
                    ability("侦查无人机", "无人机"),
                    ability("无人机电磁脉冲", "电磁脉冲")),
            rule("暴雷", aliases("暴雷")),
            rule("直布罗陀", aliases("直布罗陀", "胖胖"),
                    ability("防护穹顶", "罩子", "穹顶护盾", "泡泡盾"),
                    ability("防御轰炸", "防御性轰炸")),
            rule("地平线", aliases("地平线"),
                    ability("重力升降台", "重力升降机", "重力升降台"),
                    ability("黑洞", "黑洞")),
            rule("命脉", aliases("命脉", "奶妈"),
                    ability("战斗复活", "拉队友", "救队友", "战斗复苏", "战斗复活"),
                    ability("治疗无人机", "治疗无人机", "医疗无人机")),
            rule("罗芭", aliases("罗芭"),
                    ability("盗贼的挚友", "手镯", "瞬移手镯"),
                    ability("黑市精品店", "黑市")),
            rule("疯玛吉", aliases("疯玛吉")),
            rule("幻象", aliases("幻象"), ability("心理战", "分身", "诱饵")),
            rule("纽卡斯尔", aliases("纽卡斯尔")),
            rule("动力小子", aliases("动力小子"),
                    ability("肾上腺刺激", "兴奋剂", "打针"),
                    ability("弹射跳板", "跳板")),
            rule("探路者", aliases("探路者", "机器人"),
                    ability("钩锁", "钩锁", "抓钩"),
                    ability("滑索枪", "滑索")),
            rule("兰伯特", aliases("兰伯特")),
            rule("亡灵", aliases("亡灵"), ability("暗影扑击", "暗影突袭", "暗影扑击")),
            rule("希尔", aliases("希尔")),
            rule("飞雀", aliases("飞雀")),
            rule("瓦尔基里", aliases("瓦尔基里", "瓦鸡"),
                    ability("导弹齐射", "导弹", "飞弹蜂群", "导弹齐射"),
                    ability("升空俯冲", "天际俯冲", "飞天", "升空俯冲")),
            rule("万蒂奇", aliases("万蒂奇")),
            rule("沃特森", aliases("沃特森")),
            rule("恶灵", aliases("恶灵", "相位女"),
                    ability("进入虚空", "相位", "踏入虚空", "进入虚空"),
                    ability("维度裂隙", "传送门", "维度裂隙"))
    );

    private final RagProperties ragProperties;

    public String enhance(String query) {
        if (!ragProperties.isLegendAliasEnhancementEnabled() || query == null || query.isBlank()) {
            return query;
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        Set<String> entities = new LinkedHashSet<>();
        Set<String> abilities = new LinkedHashSet<>();
        for (LegendRule rule : RULES) {
            if (!containsAny(normalized, rule.aliases())) {
                continue;
            }
            entities.add(rule.entity());
            for (AbilityRule ability : rule.abilities()) {
                if (containsAny(normalized, ability.aliases())) {
                    abilities.add(ability.name());
                }
            }
        }
        if (entities.isEmpty()) {
            return query;
        }

        StringBuilder enhanced = new StringBuilder(query)
                .append("\n已解析英雄：")
                .append(String.join(", ", entities));
        if (!abilities.isEmpty()) {
            enhanced.append("。已解析技能：").append(String.join(", ", abilities));
        }
        return enhanced.append('。').toString();
    }

    private boolean containsAny(String query, List<String> aliases) {
        return aliases.stream().map(alias -> alias.toLowerCase(Locale.ROOT)).anyMatch(query::contains);
    }

    private static LegendRule rule(String entity, List<String> aliases, AbilityRule... abilities) {
        return new LegendRule(entity, aliases, List.of(abilities));
    }

    private static List<String> aliases(String... aliases) {
        return List.of(aliases);
    }

    private static AbilityRule ability(String name, String... aliases) {
        return new AbilityRule(name, List.of(aliases));
    }

    private record LegendRule(String entity, List<String> aliases, List<AbilityRule> abilities) {
        private LegendRule {
            aliases = List.copyOf(aliases);
            abilities = List.copyOf(new ArrayList<>(abilities));
        }
    }

    private record AbilityRule(String name, List<String> aliases) {
        private AbilityRule {
            aliases = List.copyOf(aliases);
        }
    }
}
