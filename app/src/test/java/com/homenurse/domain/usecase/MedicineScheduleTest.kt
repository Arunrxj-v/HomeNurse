package com.homenurse.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic reminder-slot mapping. Key safety property under test:
 * unrecognised frequencies produce NO times (never guess dosing), while
 * recognised ones produce stable, editable reminder slots.
 */
class MedicineScheduleTest {

    @Test
    fun `once daily maps to a single morning slot`() {
        assertEquals(listOf(8 * 60), MedicineSchedule.timesFor("once daily", null))
    }

    @Test
    fun `twice daily maps to morning and evening slots`() {
        assertEquals(listOf(8 * 60, 20 * 60), MedicineSchedule.timesFor("twice daily", null))
    }

    @Test
    fun `three times daily maps to three slots`() {
        assertEquals(
            listOf(8 * 60, 14 * 60, 20 * 60),
            MedicineSchedule.timesFor("three times a day", null),
        )
    }

    @Test
    fun `four times daily maps to four slots`() {
        assertEquals(
            listOf(8 * 60, 12 * 60, 16 * 60, 20 * 60),
            MedicineSchedule.timesFor("four times daily", null),
        )
    }

    @Test
    fun `abbreviated latin frequencies are recognised`() {
        assertEquals(listOf(8 * 60, 20 * 60), MedicineSchedule.timesFor("BD", null))
        assertEquals(listOf(8 * 60), MedicineSchedule.timesFor("OD", null))
        assertEquals(
            listOf(8 * 60, 14 * 60, 20 * 60),
            MedicineSchedule.timesFor("TDS", null),
        )
    }

    @Test
    fun `every N hours spreads slots from 08_00 to 22_00`() {
        assertEquals(
            listOf(8 * 60, 10 * 60, 12 * 60, 14 * 60, 16 * 60, 18 * 60),
            MedicineSchedule.timesFor("every 2 hours", null),
        )
        assertEquals(
            listOf(8 * 60, 12 * 60, 16 * 60, 20 * 60),
            MedicineSchedule.timesFor("every 4 hours", null),
        )
    }

    @Test
    fun `every N hours out of range yields no times`() {
        assertTrue(MedicineSchedule.timesFor("every 25 hours", null).isEmpty())
        assertTrue(MedicineSchedule.timesFor("every other day", null).isEmpty())
    }

    @Test
    fun `unrecognised frequency never guesses a time`() {
        assertTrue(MedicineSchedule.timesFor("when required for pain", null).isEmpty())
        assertTrue(MedicineSchedule.timesFor("as needed", null).isEmpty())
        assertTrue(MedicineSchedule.timesFor(null, null).isEmpty())
        assertTrue(MedicineSchedule.timesFor("", null).isEmpty())
    }

    @Test
    fun `night timing with empty frequency gets an evening slot`() {
        assertEquals(listOf(22 * 60), MedicineSchedule.timesFor("", "at night"))
        assertEquals(listOf(22 * 60), MedicineSchedule.timesFor(null, "bedtime"))
    }

    @Test
    fun `single dose timed at night is moved to the evening`() {
        assertEquals(listOf(22 * 60), MedicineSchedule.timesFor("once daily", "at night"))
    }

    @Test
    fun `multi dose frequency keeps its slots even with night timing`() {
        assertEquals(
            listOf(8 * 60, 20 * 60),
            MedicineSchedule.timesFor("twice daily", "after food, night not included"),
        )
    }

    @Test
    fun `format renders 24h zero padded time`() {
        assertEquals("08:00", MedicineSchedule.format(8 * 60))
        assertEquals("00:00", MedicineSchedule.format(0))
        assertEquals("23:59", MedicineSchedule.format(23 * 60 + 59))
        assertEquals("09:05", MedicineSchedule.format(9 * 60 + 5))
    }
}
