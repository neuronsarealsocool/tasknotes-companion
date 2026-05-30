package com.local.tasknotescompanion.quickadd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class QuickAddParserTest {
    private val parser = QuickAddParser { LocalDate.parse("2026-05-16") }

    @Test
    fun parsesJapanExampleAsScheduledDateTime() {
        val draft = parser.parse("I'll be in Japan from 18 May @7PM")

        assertEquals("I'll be in Japan from 18 May @7PM", draft.title)
        assertEquals(LocalDate.parse("2026-05-18"), draft.scheduled)
        assertEquals(LocalTime.parse("19:00"), draft.scheduledTime)
        assertNull(draft.due)
        assertEquals(emptyList<String>(), draft.contexts)
    }

    @Test
    fun parsesTodayTimeAndTag() {
        val draft = parser.parse("Buy groceries today at 3pm #errand")

        assertEquals("Buy groceries today at 3pm #errand", draft.title)
        assertEquals(LocalDate.parse("2026-05-16"), draft.scheduled)
        assertEquals(LocalTime.parse("15:00"), draft.scheduledTime)
        assertEquals(listOf("errand"), draft.tags)
    }

    @Test
    fun parsesSpacedAtTime() {
        val draft = parser.parse("Message Sam today @ 4 50 pm")

        assertEquals("Message Sam today @ 4 50 pm", draft.title)
        assertEquals(LocalDate.parse("2026-05-16"), draft.scheduled)
        assertEquals(LocalTime.parse("16:50"), draft.scheduledTime)
    }

    @Test
    fun mapsDateRangeToFirstScheduledDateOnly() {
        val draft = parser.parse("Trip from 18 May to 21 May")

        assertEquals("Trip from 18 May to 21 May", draft.title)
        assertEquals(LocalDate.parse("2026-05-18"), draft.scheduled)
        assertNull(draft.due)
        assertEquals(5_760, draft.timeEstimateMinutes)
    }

    @Test
    fun mapsHyphenDateRangeToFirstScheduledDateOnly() {
        val draft = parser.parse("I'll be in France 19 May-21 June")

        assertEquals("I'll be in France 19 May-21 June", draft.title)
        assertEquals(LocalDate.parse("2026-05-19"), draft.scheduled)
        assertNull(draft.due)
        assertEquals(48_960, draft.timeEstimateMinutes)
    }

    @Test
    fun mapsDayOnlyRangeToCurrentMonth() {
        val draft = parser.parse("I'll be in France from 22-30")

        assertEquals(LocalDate.parse("2026-05-22"), draft.scheduled)
        assertNull(draft.due)
        assertEquals(12_960, draft.timeEstimateMinutes)
    }

    @Test
    fun mapsMonthAbbreviationRangeAndReusesMonthForEndDay() {
        val draft = parser.parse("Away Mar 3-7")

        assertEquals(LocalDate.parse("2027-03-03"), draft.scheduled)
        assertNull(draft.due)
        assertEquals(7_200, draft.timeEstimateMinutes)
    }

    @Test
    fun mapsOrdinalDateRangeToTimeEstimate() {
        val draft = parser.parse("Jericho will be in Philippines from 18th May-7th June")

        assertEquals(LocalDate.parse("2026-05-18"), draft.scheduled)
        assertNull(draft.due)
        assertEquals(30_240, draft.timeEstimateMinutes)
    }

    @Test
    fun distinguishesAtTimeFromAtContext() {
        val draft = parser.parse("Pack bags @7PM @home")

        assertEquals("Pack bags @7PM @home", draft.title)
        assertEquals(LocalTime.parse("19:00"), draft.scheduledTime)
        assertEquals(listOf("home"), draft.contexts)
    }

    @Test
    fun missingYearUsesNextOccurrence() {
        val draft = parser.parse("Book hotel 15 May")

        assertEquals(LocalDate.parse("2027-05-15"), draft.scheduled)
    }

    @Test
    fun detectsWordPriorityAndIgnoresBangPunctuation() {
        val wordPriority = parser.parse("Pay invoice high priority")
        val punctuation = parser.parse("Pay invoice !!")

        assertEquals("Pay invoice high priority", wordPriority.title)
        assertEquals("high", wordPriority.priority)
        assertEquals("Pay invoice !!", punctuation.title)
        assertNull(punctuation.priority)
    }

    @Test
    fun canTargetSingleDateToDue() {
        val draft = parser.parse("Submit form tomorrow at 9am", QuickAddDateTarget.DUE)

        assertNull(draft.scheduled)
        assertEquals(LocalDate.parse("2026-05-17"), draft.due)
        assertEquals(LocalTime.parse("09:00"), draft.dueTime)
    }
}
