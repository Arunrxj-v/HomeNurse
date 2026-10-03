package com.homenurse.domain.usecase

/**
 * Deterministic mapping from a CONFIRMED frequency/timing string to reminder
 * times (minutes since midnight).
 *
 * The frequency itself always comes from the user's document + review; these
 * times are only *reminder slots* (editable in the Care Plan screen), never
 * presented as prescribed dosing times. Unrecognised frequencies return an
 * empty list — the task is created without a reminder instead of guessing.
 */
object MedicineSchedule {

    const val MIN_06_00 = 6 * 60
    const val MIN_08_00 = 8 * 60
    const val MIN_10_00 = 10 * 60
    const val MIN_12_00 = 12 * 60
    const val MIN_14_00 = 14 * 60
    const val MIN_16_00 = 16 * 60
    const val MIN_18_00 = 18 * 60
    const val MIN_20_00 = 20 * 60
    const val MIN_21_00 = 21 * 60
    const val MIN_22_00 = 22 * 60

    fun timesFor(frequency: String?, timing: String?): List<Int> {
        val nightTiming = timing?.contains("night", ignoreCase = true) == true ||
            timing?.contains("bedtime", ignoreCase = true) == true
        val f = frequency?.lowercase()?.trim().orEmpty()

        val times = when {
            f.isEmpty() && nightTiming -> listOf(MIN_22_00)
            f.isEmpty() -> emptyList()

            "every" in f -> everyHoursTimes(f)

            "four" in f || "qds" in f || "qid" in f ->
                listOf(MIN_08_00, MIN_12_00, MIN_16_00, MIN_20_00)

            "three" in f || "thrice" in f || "tds" in f ->
                listOf(MIN_08_00, MIN_14_00, MIN_20_00)

            "twice" in f || "bd" in f -> listOf(MIN_08_00, MIN_20_00)

            "once" in f || "od" in f -> listOf(MIN_08_00)

            "as directed" in f || "stat" in f ->
                if (nightTiming) listOf(MIN_22_00) else emptyList()

            else -> emptyList()
        }

        // A dose explicitly timed "at night"/"bedtime" always gets an evening slot.
        if (nightTiming && times.isNotEmpty() && MIN_22_00 !in times && times.size == 1) {
            return listOf(MIN_22_00)
        }
        return times
    }

    private fun everyHoursTimes(frequency: String): List<Int> {
        val hours = Regex("""every\s+(\d+)""").find(frequency)?.groupValues?.get(1)?.toIntOrNull()
            ?: return emptyList()
        if (hours !in 1..12) return emptyList()
        val times = mutableListOf<Int>()
        var current = MIN_08_00
        while (current <= MIN_22_00 && times.size < 6) {
            times += current
            current += hours * 60
        }
        return times
    }

    /** Render minutes-since-midnight as HH:MM (24h). */
    fun format(timeOfDayMin: Int): String {
        val hour = timeOfDayMin / 60
        val minute = timeOfDayMin % 60
        return "%02d:%02d".format(hour, minute)
    }
}
