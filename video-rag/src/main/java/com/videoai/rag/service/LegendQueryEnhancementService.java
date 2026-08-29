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
 * 将已确认的中文英雄名、玩家俗称和技能俗称解析为英文知识库实体。
 * 规则保持小而明确：只有先命中英雄别名，才解析该英雄名下的技能，避免把“烟”“扫描”等通用词误扩展。
 */
@Service
@RequiredArgsConstructor
public class LegendQueryEnhancementService {

    private static final List<LegendRule> RULES = List.of(
            rule("Alter", aliases("变幻")),
            rule("Ash", aliases("艾许", "艾什"), ability("Arc Snare", "电弧绊索")),
            rule("Ballistic", aliases("弹道")),
            rule("Bloodhound", aliases("寻血猎犬", "猎犬", "狗子"),
                    ability("Eye of the Allfather", "扫描", "上帝之眼"),
                    ability("Beast of the Hunt", "狩猎野兽")),
            rule("Catalyst", aliases("催化姬")),
            rule("Caustic", aliases("腐蚀"),
                    ability("Nox Gas Trap", "毒气罐", "毒罐"),
                    ability("Nox Gas Grenade", "毒气手雷")),
            rule("Conduit", aliases("导线管")),
            rule("Crypto", aliases("密客"),
                    ability("Surveillance Drone", "无人机"),
                    ability("Drone EMP", "emp", "电磁脉冲")),
            rule("Fuse", aliases("暴雷")),
            rule("Gibraltar", aliases("直布罗陀", "胖胖"),
                    ability("Dome of Protection", "罩子", "穹顶护盾", "泡泡盾"),
                    ability("Defensive Bombardment", "防御性轰炸")),
            rule("Horizon", aliases("地平线"),
                    ability("Gravity Lift", "重力升降机"),
                    ability("Black Hole", "黑洞")),
            rule("Lifeline", aliases("命脉", "奶妈"),
                    ability("Combat Revive", "拉队友", "救队友", "战斗复苏"),
                    ability("D.O.C. Heal Drone", "治疗无人机", "医疗无人机")),
            rule("Loba", aliases("罗芭"),
                    ability("Burglar's Best Friend", "手镯", "瞬移手镯"),
                    ability("Black Market Boutique", "黑市")),
            rule("Mad Maggie", aliases("疯玛吉")),
            rule("Mirage", aliases("幻象"), ability("Psyche Out", "分身", "诱饵")),
            rule("Newcastle", aliases("纽卡斯尔")),
            rule("Octane", aliases("动力小子"),
                    ability("Stim", "兴奋剂", "打针"),
                    ability("Launch Pad", "跳板")),
            rule("Pathfinder", aliases("探路者", "机器人"),
                    ability("Grappling Hook", "钩锁", "抓钩"),
                    ability("Zipline Gun", "滑索")),
            rule("Rampart", aliases("兰伯特")),
            rule("Revenant", aliases("亡灵"), ability("Shadow Pounce", "暗影突袭")),
            rule("Seer", aliases("希尔")),
            rule("Sparrow", aliases("飞雀")),
            rule("Valkyrie", aliases("瓦尔基里", "瓦鸡"),
                    ability("Missile Swarm", "导弹", "飞弹蜂群"),
                    ability("Skyward Dive", "天际俯冲", "飞天")),
            rule("Vantage", aliases("万蒂奇")),
            rule("Wattson", aliases("沃特森")),
            rule("Wraith", aliases("恶灵", "相位女"),
                    ability("Into the Void", "相位", "踏入虚空"),
                    ability("Dimensional Rift", "传送门", "维度裂隙"))
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
                .append("\nResolved Apex Legend: ")
                .append(String.join(", ", entities));
        if (!abilities.isEmpty()) {
            enhanced.append(". Resolved ability: ").append(String.join(", ", abilities));
        }
        return enhanced.append('.').toString();
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
