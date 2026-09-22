package com.ledger.offline.parse

import com.ledger.offline.parse.ProbeLog.ProbeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 诊断缓冲的编解码。
 *
 * 这块最容易被写错又最难发现：分隔 / 转义出问题时不会报错，
 * 只是字段悄悄错位或整条丢失——而它偏偏是排查故障时唯一的信息来源，
 * 静默失效等于没有。
 */
class ProbeLogTest {

    private fun entry(
        at: Long = 1_700_000_000_000L,
        pkg: String = "com.eg.android.AlipayGphone",
        title: String = "支付宝",
        text: String = "你在星巴克消费25.00元，付款成功",
        outcome: String? = "ADDED",
        drop: DropReason? = null
    ) = ProbeEntry(at, pkg, "alipay", title, text, outcome, drop)

    @Test
    fun `编解码往返不丢字段`() {
        val e = entry()
        val decoded = ProbeLog.decodeAll(ProbeLog.encodeAll(listOf(e)))
        assertEquals(1, decoded.size)
        assertEquals(e, decoded[0])
    }

    @Test
    fun `原文里的换行不会把一条拆成两条`() {
        // 通知正文常常是多行（EXTRA_TEXT_LINES 拼出来的），
        // 换行若破坏「一行一条」的格式，后面所有记录都会错位
        val e = entry(text = "第一行\n第二行\r\n第三行")
        val decoded = ProbeLog.decodeAll(ProbeLog.encodeAll(listOf(e, entry())))
        assertEquals(2, decoded.size)
        assertEquals("第一行\n第二行\r\n第三行", decoded[0].text)
    }

    @Test
    fun `反斜杠不会被转义吃掉`() {
        val e = entry(text = "路径 C:\\Users\\test \\n 不是换行")
        val decoded = ProbeLog.decodeAll(ProbeLog.encodeAll(listOf(e)))
        assertEquals("路径 C:\\Users\\test \\n 不是换行", decoded[0].text)
    }

    @Test
    fun `损坏的行被跳过而不是整份报废`() {
        val text = ProbeLog.encodeAll(listOf(entry())) + "\n坏掉的一行\n" +
            ProbeLog.encodeAll(listOf(entry(at = 2L)))
        val decoded = ProbeLog.decodeAll(text)
        assertEquals(2, decoded.size)
        assertEquals(2L, decoded[1].at)
    }

    @Test
    fun `追加时同一条通知不重复占位`() {
        var list = emptyList<ProbeEntry>()
        list = ProbeLog.push(list, entry())
        list = ProbeLog.push(list, entry())
        list = ProbeLog.push(list, entry())
        assertEquals(1, list.size)
    }

    @Test
    fun `同文案但间隔超过重发窗口的是两笔交易`() {
        // 2026-09-22 真机踩坑：连付两笔 1 元，通知原文一字不差。
        // 无限期按文案去重会让第二笔从诊断列表消失，排查被带偏。
        var list = emptyList<ProbeEntry>()
        list = ProbeLog.push(list, entry(at = 100L))
        // 恰好在窗口边界外一毫秒
        list = ProbeLog.push(list, entry(at = 100L + ProbeLog.RESEND_WINDOW_MS + 1))
        assertEquals(2, list.size)
        assertEquals(100L, list[0].at)
        assertEquals(100L + ProbeLog.RESEND_WINDOW_MS + 1, list[1].at)
    }

    @Test
    fun `窗口内的重发仍然只留一条`() {
        var list = emptyList<ProbeEntry>()
        list = ProbeLog.push(list, entry(at = 100L))
        list = ProbeLog.push(list, entry(at = 100L + ProbeLog.RESEND_WINDOW_MS - 1))
        assertEquals(1, list.size)
        // 留下的是时间戳更新的那次
        assertEquals(100L + ProbeLog.RESEND_WINDOW_MS - 1, list[0].at)
    }

    @Test
    fun `超过上限保留最新的`() {
        var list = emptyList<ProbeEntry>()
        for (i in 1..30) {
            list = ProbeLog.push(list, entry(at = i.toLong(), text = "第${i}条"))
        }
        assertEquals(ProbeLog.LIMIT, list.size)
        assertEquals(30L, list.last().at)     // 最新的一条在末尾
        assertTrue(list.none { it.at == 1L }) // 最老的一条已被挤掉
    }

    @Test
    fun `丢弃原因的枚举名要能还原`() {
        for (reason in DropReason.values()) {
            val e = entry(outcome = null, drop = reason)
            val decoded = ProbeLog.decodeAll(ProbeLog.encodeAll(listOf(e)))
            assertEquals(reason, decoded[0].drop)
            assertEquals(null, decoded[0].outcome)
        }
    }

    @Test
    fun `标签是给人看的，不许是枚举名`() {
        // 诊断页直接展示 label，写成 NO_DIRECTION 用户读不懂
        for (reason in DropReason.values()) {
            assertTrue("${reason.name} 的 label 不该是枚举名", reason.label != reason.name)
            assertTrue(reason.label.isNotBlank())
        }
    }
}
