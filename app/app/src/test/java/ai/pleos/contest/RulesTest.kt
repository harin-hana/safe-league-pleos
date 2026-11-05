package ai.pleos.contest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private val s = Settings()
    private val m0 = Rules.rollover(Memo(), "2026-10-26", "2026-W44")

    @Test fun coffeeOnlyInCommuteWithRoute() {
        assertTrue(Rules.coffeeDue(s, m0, 8, true))
        assertFalse(Rules.coffeeDue(s, m0, 10, true))
        assertFalse(Rules.coffeeDue(s, m0, 8, false))
        assertTrue(Rules.coffeeDue(s.copy(demo = true), m0, 22, false))
    }

    @Test fun laterHidesTodayAndRestsWeekAfterThree() {
        val c = Rules.coffeeCard(s)
        var m = Rules.later(m0, c)
        assertFalse(Rules.coffeeDue(s, m, 8, true))
        m = Rules.rollover(m, "2026-10-27", "2026-W44")
        assertTrue(Rules.coffeeDue(s, m, 8, true))
        m = Rules.later(Rules.later(m, c), c).copy(coffeeHiddenDay = "")
        assertEquals(3, m.laterCount)
        assertFalse(Rules.coffeeDue(s, m, 8, true))
        m = Rules.rollover(m, "2026-11-02", "2026-W45")
        assertTrue(Rules.coffeeDue(s, m, 8, true))
    }

    @Test fun dailyCapTwo() {
        val c = Rules.coffeeCard(s)
        val m = Rules.shownCard(s, Rules.shownCard(s, m0, c), c)
        assertFalse(Rules.coffeeDue(s, m, 8, true))
        assertTrue(Rules.coffeeDue(s.copy(dailyCap = false), m, 8, true))
        assertEquals(0, Rules.rollover(m, "2026-10-27", "2026-W44").shown)
    }

    @Test fun chargeOncePerStage() {
        assertEquals(0, Rules.chargeStage(s, 20))
        assertEquals(1, Rules.chargeStage(s, 19))
        var m = m0
        assertTrue(Rules.chargeDue(s, m, 1))
        m = Rules.shownCard(s, m, Rules.chargeCard(s, 18, false), 1)
        assertEquals(0, m.shown) // 기본: 충전은 하루 2번에 안 셈
        assertFalse(Rules.chargeDue(s, m, 1))
        m = Rules.afterStage(m, 0) // 충전해서 기준 위로
        assertTrue(Rules.chargeDue(s, m, 1))
    }

    @Test fun rangeRule() {
        assertEquals(1, Rules.chargeStage(s, 80, rangeKm = 40.0, needKm = 20.0))
        assertEquals(0, Rules.chargeStage(s, 80, rangeKm = 60.0, needKm = 20.0))
        assertEquals(1, Rules.chargeStage(s, 10, rangeKm = 60.0, needKm = 20.0))
        assertEquals(0, Rules.chargeStage(s, 80, rangeKm = 40.0, needKm = null))
    }

    @Test fun cardCopyHasNoMiddleDotOrQuestion() {
        val cards = Criterion.values().map { Rules.coffeeCard(s.copy(criterion = it)) } +
            Rules.chargeCard(s, 18, false) + Rules.chargeCard(s, 18, true)
        cards.flatMap { listOf(it.fact, it.tag, it.reason, it.yes) }.forEach {
            assertFalse(it, it.contains('·') || it.contains('?'))
        }
        assertEquals("배터리 18%, 도착 전 충전소 1곳", Rules.chargeCard(s, 18, false).fact)
        assertEquals("20% 아래면 알리기로 하셨어요", Rules.chargeCard(s, 18, false).reason)
    }
}
