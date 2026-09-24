package com.ledger.offline.data

/**
 * 预置分类清单（v4 起新增，落 categories 表）。
 *
 * 设计取舍（2026-09-23 与用户确认）：
 * - 一级 13 个：沿用 classify_rules.json 的 10 个既有 id（food…investment、transfer）
 *   + 兜底 other，另补 social（社交）、travel（旅行）。**既有 id 一个都不能少**——
 *   历史账目的 category_id 迁移后必须仍是合法值，零破坏是硬约束。
 * - 常用一级配 3~6 个二级；「转账」「其他」是形态/兜底，不配二级。
 * - name 在表上有 UNIQUE 约束：参考 App 里「购物」「娱乐」各出现两次是反面教材，
 *   重名分类只会让人在统计里把两笔账算错。
 * - sort 全表唯一且单调：一级取 10、20、30…，其二级取 父级值+1、+2…
 *   （11…15 落在 10 与 20 之间），全表按 sort 排序即为「一级带二级」的展示顺序。
 */
object CategoryPresets {

    data class Preset(val id: String, val name: String, val parentId: String, val sort: Int)

    /** 兜底分类的 id。与 classify_rules.json 的 fallback.id 必须一致，有单测交叉校验 */
    const val FALLBACK_ID = "other"

    /** 「电费-车」（电动车电费）的 id。DB v6 迁移对已装用户补种这一条，有单测锁定 */
    const val CAR_POWER_ID = "living.carPower"

    /** 记一笔的默认分类（用户高频场景是买菜做饭，0.2.10 按用户要求从「其他」改为「餐饮-买菜」） */
    const val DEFAULT_ADD_CATEGORY_ID = "food.grocery"

    val ALL: List<Preset> = listOf(
        // ---------- 一级 + 餐饮二级（8 个） ----------
        Preset("food", "餐饮", "", 10),
        Preset("food.breakfast", "早餐", "food", 11),
        Preset("food.lunch", "午餐", "food", 12),
        Preset("food.dinner", "晚餐", "food", 13),
        Preset("food.takeout", "外卖", "food", 14),
        Preset("food.snacks", "零食饮料", "food", 15),
        Preset("food.fruit", "水果", "food", 16),
        Preset("food.grocery", "买菜", "food", 17),
        Preset("food.seasoning", "油盐酱醋", "food", 18),

        // ---------- 交通（5 个） ----------
        Preset("transport", "交通", "", 20),
        Preset("transport.transit", "公交地铁", "transport", 21),
        Preset("transport.taxi", "打车", "transport", 22),
        Preset("transport.fuel", "加油充电", "transport", 23),
        Preset("transport.parking", "停车过路", "transport", 24),
        Preset("transport.ticket", "火车机票", "transport", 25),

        // ---------- 购物（4 个） ----------
        Preset("shopping", "购物", "", 30),
        Preset("shopping.clothes", "服饰鞋包", "shopping", 31),
        Preset("shopping.daily", "日用百货", "shopping", 32),
        Preset("shopping.electronics", "数码电器", "shopping", 33),
        Preset("shopping.beauty", "美妆护肤", "shopping", 34),

        // ---------- 生活缴费（4 个） ----------
        Preset("living", "生活缴费", "", 40),
        Preset("living.phone", "话费网费", "living", 41),
        Preset("living.utilities", "水电燃气", "living", 42),
        Preset("living.property", "物业保洁", "living", 43),
        // 电动车电费：与家里电费走同一条生活缴费口径（0.2.10 按用户要求新增）
        Preset(CAR_POWER_ID, "电费-车", "living", 44),

        // ---------- 医疗（2 个） ----------
        Preset("medical", "医疗", "", 50),
        Preset("medical.medicine", "药品", "medical", 51),
        Preset("medical.clinic", "门诊体检", "medical", 52),

        // ---------- 娱乐（4 个） ----------
        Preset("entertainment", "娱乐", "", 60),
        Preset("entertainment.travel", "旅游", "entertainment", 61),
        Preset("entertainment.game", "游戏娱乐", "entertainment", 62),
        Preset("entertainment.sports", "运动健身", "entertainment", 63),
        Preset("entertainment.pets", "花鸟宠物", "entertainment", 64),

        // ---------- 教育（3 个） ----------
        Preset("education", "教育", "", 70),
        Preset("education.course", "课程培训", "education", 71),
        Preset("education.books", "书报文具", "education", 72),
        Preset("education.toddler", "幼儿教育", "education", 73),

        // ---------- 住房（3 个） ----------
        Preset("housing", "住房", "", 80),
        Preset("housing.rent", "房租", "housing", 81),
        Preset("housing.mortgage", "房贷", "housing", 82),
        Preset("housing.renovation", "装修维修", "housing", 83),

        // ---------- 旅行 ----------
        Preset("travel", "旅行", "", 90),
        Preset("travel.hotel", "酒店民宿", "travel", 91),
        Preset("travel.flight", "机票火车", "travel", 92),
        Preset("travel.tickets", "门票游玩", "travel", 93),

        // ---------- 社交（3 个） ----------
        Preset("social", "社交", "", 100),
        Preset("social.gifts", "人情往来", "social", 101),
        Preset("social.party", "聚会请客", "social", 102),
        Preset("social.filial", "孝敬", "social", 103),

        // ---------- 投资理财 ----------
        Preset("investment", "投资理财", "", 110),
        Preset("investment.fund", "基金股票", "investment", 111),
        Preset("investment.insurance", "保险", "investment", 112),

        // ---------- 转账（形态类，无二级） ----------
        Preset("transfer", "转账", "", 120),

        // ---------- 兜底（关键词与官方种子都未命中时的归宿） ----------
        Preset("other", "其他", "", 130)
    )

    /** 全部一级 id（parentId 为空）。统计聚合与回退兜底都以这批为锚点 */
    val TOP_IDS: Set<String> = ALL.filter { it.parentId.isEmpty() }.map { it.id }.toSet()
}
