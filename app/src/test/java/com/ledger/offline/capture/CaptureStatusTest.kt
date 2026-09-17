package com.ledger.offline.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集状态判定的单测。
 *
 * 这两条判据都会直接决定界面上弹不弹告警，而弹错任何一边都有代价：
 *   - 该弹不弹 → 用户以为在自动记账，实际一笔没记，两个月后对账才发现（silent failure）
 *   - 不该弹乱弹 → 狼来了，用户学会无视告警，真出问题时也不再理会
 * 所以边界值（39/40/41 天）单独钉死。
 */
class CaptureStatusTest {

    private val day = 24 * 60 * 60 * 1000L
    private val now = 1786468540000L   // 固定时间，不依赖运行时区

    @Test
    fun `从未导入过账单视为超期`() {
        val s = CaptureStatus.Snapshot(lastImportAt = 0L)
        assertTrue(s.importStale(now))
        assertEquals(-1L, s.daysSinceImport(now))
    }

    @Test
    fun `导入超期的边界是 40 天`() {
        assertFalse(CaptureStatus.Snapshot(lastImportAt = now - 39 * day).importStale(now))
        assertFalse(CaptureStatus.Snapshot(lastImportAt = now - 40 * day).importStale(now))
        assertTrue(CaptureStatus.Snapshot(lastImportAt = now - 41 * day).importStale(now))
    }

    @Test
    fun `已授权但服务从未连上才判定为掉线`() {
        assertTrue(CaptureStatus.Snapshot(granted = true, everConnected = false).listenerStalled)
        assertFalse(CaptureStatus.Snapshot(granted = true, everConnected = true).listenerStalled)
        // 未授权时走的是另一条引导（permissionBanner），不该同时弹掉线告警
        assertFalse(CaptureStatus.Snapshot(granted = false, everConnected = false).listenerStalled)
    }

    @Test
    fun `最近监听到通知的天数按整天取整`() {
        assertEquals(3L, CaptureStatus.Snapshot(lastEventAt = now - 3 * day - 60_000L).daysSinceEvent(now))
        assertEquals(-1L, CaptureStatus.Snapshot(lastEventAt = 0L).daysSinceEvent(now))
    }
}
