package com.heasafe.safestream.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityLogTest {

    @Test
    fun `最新事件排在最前`() {
        val log = SecurityLog()
        log.record("a")
        log.record("b")
        assertEquals(listOf("b", "a"), log.all())
    }

    @Test
    fun `超过容量时丢弃最旧的事件`() {
        val log = SecurityLog()
        repeat(SecurityLog.CAPACITY + 50) { log.record("e$it") }
        assertEquals(SecurityLog.CAPACITY, log.all().size)
        assertEquals("最新一条保留", "e${SecurityLog.CAPACITY + 49}", log.all().first())
        assertEquals("最旧的 50 条被丢弃", "e50", log.all().last())
    }

    @Test
    fun `clear 后为空`() {
        val log = SecurityLog()
        log.record("a")
        log.clear()
        assertEquals(0, log.all().size)
    }

    @Test
    fun `count 汇总记录条数`() {
        val log = SecurityLog()
        log.record("a")
        log.record("b")
        assertEquals(2, log.count())
    }
}
