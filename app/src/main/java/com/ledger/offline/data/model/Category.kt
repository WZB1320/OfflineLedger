package com.ledger.offline.data.model

/**
 * 分类树上的一格。
 *
 * - 一级：parentId 为空串；二级：parentId 指向某个一级的 id。
 * - 交易记录（txn.category_id）永远指向**最细粒度**——记到二级就存二级 id，
 *   统计时按 [parentId] 折回一级聚合。
 * - isCustom = false 是预置分类（不可删），true 是用户自建（可增删排序）。
 */
data class Category(
    val id: String,
    val name: String,
    val parentId: String,
    val isCustom: Boolean,
    /** 同级内的展示顺序。全表唯一，交换两行 sort 即完成上移/下移 */
    val sort: Int
)
