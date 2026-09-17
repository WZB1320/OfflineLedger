package com.ledger.offline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 指纹判重语句的**契约测试**。
 *
 * SQL 本身要真机（SQLite）才跑得起来，但「守卫有没有被误删」不该等到真机才发现——
 * 这一条守卫生效与否，直接决定「同商户 3 分钟内的两笔真实消费会不会少记一笔」。
 *
 * 与 [MergeMatcher.orderNumbersCompatible] 是同一语义的两处实现：
 * 两边都有单号且不一致 ⇒ 可证明是两笔不同交易，不得靠指纹判重。
 * 两处必须同时存在，缺一处就会出现「上一层正确判为新增、下一层又被吞掉」的漏账。
 */
class DuplicateProbeSqlTest {

    @Test
    fun `带单号时语句必须含单号守卫`() {
        val sql = TransactionDao.duplicateProbeSql(hasTxnNo = true)
        assertTrue(
            "带单号却没排除「既有记录单号不同」的行：同商户 3 分钟内两笔同金额消费，" +
                "后一笔会被当成重复丢弃。语句：$sql",
            sql.contains("txn_no = '' OR txn_no = ?")
        )
    }

    @Test
    fun `不带单号时不应引入单号条件`() {
        val sql = TransactionDao.duplicateProbeSql(hasTxnNo = false)
        assertFalse("没有单号可比，加条件只会让判重永远不命中：$sql", sql.contains("txn_no"))
    }

    /** 占位符个数必须与实际绑定参数个数一致，否则运行时绑定会错位 */
    @Test
    fun `占位符个数与绑定参数个数一致`() {
        // 不带单号：amount_hash, merchant_hash, direction, occurred_at 下界、上界 = 5
        assertEquals(5, TransactionDao.duplicateProbeSql(hasTxnNo = false).count { it == '?' })
        // 带单号：再加 txn_no = 6
        assertEquals(6, TransactionDao.duplicateProbeSql(hasTxnNo = true).count { it == '?' })
    }

    /** 判重的基本形状：金额指纹 + 商户指纹 + 方向 + 时间窗 */
    @Test
    fun `语句形状与去重维度`() {
        val sql = TransactionDao.duplicateProbeSql(hasTxnNo = false)
        assertTrue(sql.contains("amount_hash = ?"))
        assertTrue(sql.contains("merchant_hash = ?"))
        assertTrue(sql.contains("direction = ?"))
        assertTrue("必须限定时间窗，否则同金额的历史记录会永久拦截新交易",
            sql.contains("occurred_at BETWEEN ? AND ?"))
        assertTrue("必须 LIMIT 1，只需知道有没有", sql.contains("LIMIT 1"))
    }
}
