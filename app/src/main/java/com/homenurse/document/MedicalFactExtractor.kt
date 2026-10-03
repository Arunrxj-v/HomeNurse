package com.homenurse.document

import com.homenurse.domain.model.FactStatus
import com.homenurse.domain.model.FactType
import com.homenurse.domain.model.MedicalFact
import com.homenurse.domain.util.Clock
import com.homenurse.domain.util.IdGenerator

/**
 * Deterministic extraction of structured medical facts from OCR text.
 *
 * Rules:
 *  - Every field is copied verbatim from the source line (provenance kept in
 *    [MedicalFact.sourceText]); nothing is inferred, completed or invented.
 *  - Extraction is pattern-based only — no diagnoses, dosages or
 *    interpretations are fabricated. Lines that do not match a known
 *    document pattern produce NO fact.
 *  - Geometry is used where available: lines that sit on the same visual
 *    row (bounding-box vertical overlap) are merged into one logical row so
 *    `Hemoglobin` and `13.2 g/dL` — often returned as separate blocks by
 *    the recognizer — stay associated as label → value instead of
 *    degrading into an unordered text blob.
 *  - Every fact is created PENDING and must be confirmed by the user before
 *    it can influence any medical screen or the on-device AI.
 *  - Confidence combines the pattern score with the recognizer's line
 *    confidence; a line the normalizer had to correct (or a garbled-looking
 *    medicine name) is capped so the item shows up as "needs verification".
 */
object MedicalFactExtractor {

    private const val MAX_FACTS = 200

    // --- shared building blocks ---------------------------------------------------

    private val doseRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*(mg|mcg|µg|ug|g|ml|iu|units?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val percentRegex = Regex("""\b(\d+(?:\.\d+)?)\s*%""")
    private val labValueRegex = Regex(
        """\b(\d+(?:\.\d+)?)\s*(g/dl|g/l|mg/dl|mg/l|mcg/dl|mmol/l|mmol/mol|nmol/l|pmol/l|iu/l|miu/l|ng/ml|pg/ml|u/l|meq/l|ml/min|bpm|10\^?\s*3/µl|10\^?\s*9/l|k/µl|cells/µl|cells/mm3|mmhg)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bpPairRegex = Regex("""\b(\d{2,3}\s*/\s*\d{2,3})\s*mmhg\b""", RegexOption.IGNORE_CASE)
    private val referenceRangeRegex = Regex(
        """(?:ref(?:erence)?\s*(?:range)?|normal(?:\s*range)?)\s*:?\s*(\d+(?:\.\d+)?\s*[-–]\s*\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val leadingNoiseRegex = Regex("""^[\s•\-–—*#]+|^\d+[.)]\s*""")
    private val labelRegex = Regex(
        """^(name|age|sex|gender|date of birth|dob|patient)\s*[:\-]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val allergyToRegex = Regex("""\ballergic\s+to\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val allergyLabelRegex = Regex(
        """^(?:known\s+)?allerg(?:y|ies|ic)\s*[:\-]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val negativeAllergyRegex =
        Regex("""\b(?:no known|nkda|nka|nil|none)\b""", RegexOption.IGNORE_CASE)
    private val conditionLabelRegex = Regex(
        """^(?:diagnosis|dx|impression|provisional diagnosis|condition)\s*[:\-]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val diagnosedWithRegex =
        Regex("""\bdiagnosed\s+(?:with|as)\s+(.+?)(?:[.;]|$)""", RegexOption.IGNORE_CASE)
    private val knownCaseRegex =
        Regex("""\bknown\s+case\s+of\s+(.+?)(?:[.;]|$)""", RegexOption.IGNORE_CASE)
    private val warningRegex = Regex(
        """(?:watch\s+for|warning\s+signs?|return\s+immediately\s+if|go\s+to\s+(?:the\s+)?(?:hospital|emergency|ER)\s+if|call\s+(?:your|the)\s+(?:doctor|hospital)\s+if|seek\s+(?:immediate|urgent)\s+(?:care|medical|help))""",
        RegexOption.IGNORE_CASE,
    )
    private val followUpRegex = Regex(
        """(?:follow[\s-]?up|^review\b|review\s+(?:after|in|on)|^return\s+(?:after|in|on|within)|^visit\s+(?:after|in|on)|come\s+back\s+(?:after|in|on))""",
        RegexOption.IGNORE_CASE,
    )
    private val procedureRegex = Regex(
        """^(?:procedure|operation|surgery)\s*[:\-]\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val formPrefixRegex = Regex(
        """^(tab\.?|tablet|cap\.?|capsule|syrup|suspension|solution|injection|drops?|ointment|cream|gel|spray|inhaler|sachet|granules)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val instructionRegex = Regex(
        """^(?:take|swallow|apply|use|avoid|do\s+not|don'?t|continue|stop|drink|inject|wear|instill)\b""",
        RegexOption.IGNORE_CASE,
    )

    private data class FrequencyPattern(val regex: Regex, val label: String)

    private val frequencyPatterns = listOf(
        FrequencyPattern(
            Regex("""\btwice\s+(?:a\s+day|daily)\b|\b2-0-2\b|\b1-0-1\b|\bbd\b|\bb\.?d\.?\b""", RegexOption.IGNORE_CASE),
            "Twice daily",
        ),
        FrequencyPattern(
            Regex("""\bthrice\s+(?:a\s+day|daily)\b|\bthree\s+times\s+(?:a\s+day|daily)\b|\btds\b|\bt\.?d\.?s\.?\b|\b1-1-1\b""", RegexOption.IGNORE_CASE),
            "Three times daily",
        ),
        FrequencyPattern(
            Regex("""\bfour\s+times\s+(?:a\s+day|daily)\b|\bqds\b|\bq\.?d\.?s\.?\b|\bqid\b|\bq\.?i\.?d\.?\b""", RegexOption.IGNORE_CASE),
            "Four times daily",
        ),
        FrequencyPattern(
            Regex("""\bonce\s+(?:a\s+day|daily)\b|\b1-0-0\b|\bod\b|\bo\.?d\.?\b""", RegexOption.IGNORE_CASE),
            "Once daily",
        ),
        FrequencyPattern(
            Regex("""\b(?:one|two|three|four|\d+)\s+times?\s+(?:a\s+day|daily)\b""", RegexOption.IGNORE_CASE),
            "TIMES_A_DAY",
        ),
        FrequencyPattern(
            Regex("""\bevery\s+(\d+)\s*(?:hours?|hrs?)\b""", RegexOption.IGNORE_CASE),
            "EVERY_N_HOURS",
        ),
        FrequencyPattern(
            Regex("""\bas\s+(?:directed|per\s+(?:advice|prescription))\b""", RegexOption.IGNORE_CASE),
            "As directed",
        ),
    )

    private val timingPatterns = listOf(
        Regex("""\bafter\s+(?:food|meals?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bbefore\s+(?:food|meals?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwith\s+(?:food|meals?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bempty\s+stomach\b""", RegexOption.IGNORE_CASE),
        Regex("""\bat\s+(?:bed\s*time|night|bedtime)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bin\s+the\s+morning\b""", RegexOption.IGNORE_CASE),
    )

    private val durationRegex = Regex(
        """\b(?:for\s+)?(\d+)\s*(days?|weeks?|months?)\b|\buntil\s+(?:finished|complete[d]?|done|advised)\b""",
        RegexOption.IGNORE_CASE,
    )

    // Structured medication fields — all matched verbatim from the row.
    private val quantityRegex = Regex(
        """\b(?:one|two|three|four|five|\d+)\s+(?:tablet|capsule|tab|cap|ml|mls|puff|puffs|sachet|sachets|drop|drops|teaspoonful|tablespoon)s?\b""",
        RegexOption.IGNORE_CASE,
    )
    private val routePatterns = listOf(
        Regex("""\bby\s+mouth\b""", RegexOption.IGNORE_CASE),
        Regex("""\borally\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsublingual(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\btopical(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bintravenous(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bintramuscular(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\bsubcutaneous(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\brectal(?:ly)?\b""", RegexOption.IGNORE_CASE),
        Regex("""\binhal(?:ation|e)\b""", RegexOption.IGNORE_CASE),
    )
    private val instructionPhrasePatterns = listOf(
        Regex("""\bas\s+directed\b""", RegexOption.IGNORE_CASE),
        Regex("""\bashake\s+well\b""", RegexOption.IGNORE_CASE),
        Regex("""\bas\s+needed\b|\bprn\b""", RegexOption.IGNORE_CASE),
        Regex("""\bwith\s+(?:plenty\s+of\s+)?water\b""", RegexOption.IGNORE_CASE),
        Regex("""\brepeat\s+(?:if|required|required\s+in)\b""", RegexOption.IGNORE_CASE),
    )

    private val stripLeadVerbRegex = Regex(
        """^(?:take|swallow|eat|apply|use|consume|drink|give|instill|put)\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val leadingQuantityRegex = Regex(
        """^(?:one|two|three|four|\d+)\s+(?:tablet|capsule|tab|cap|ml|teaspoonful|sachet|drop|puff)s?\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val nameStopWords = setOf(
        "to", "the", "a", "an", "on", "over", "under", "into", "locally",
        "topically", "as", "directed", "with", "for", "and", "or", "apply",
        "use", "affected", "area", "twice", "daily", "once",
    )

    // --- entry points ---------------------------------------------------------------

    /** Legacy/plain-string entry (tests, fakes without geometry). */
    fun extract(
        documentId: String,
        lines: List<String>,
        ids: IdGenerator,
        clock: Clock,
    ): List<MedicalFact> = extract(
        documentId,
        lines.map { NormalizedLine(text = it, raw = it, page = 1, box = null, confidence = null, uncertain = false) },
        ids,
        clock,
    )

    /** Primary entry: normalized lines with provenance and geometry. */
    @JvmName("extractNormalized")
    fun extract(
        documentId: String,
        lines: List<NormalizedLine>,
        ids: IdGenerator,
        clock: Clock,
    ): List<MedicalFact> {
        val facts = mutableListOf<MedicalFact>()
        val seen = mutableSetOf<String>()
        for (row in buildRows(lines)) {
            if (facts.size >= MAX_FACTS) break
            val sourceText = row.sourceText.trim()
            if (sourceText.length < 4) continue
            val text = clean(row.text)
            if (text.isBlank()) continue
            if (!seen.add(text.lowercase())) continue

            val built = buildFact(documentId, row.copy(text = text, sourceText = sourceText), ids)
                ?: continue
            facts += built
        }
        return facts
    }

    // --- layout: merge lines of one visual row ---------------------------------------

    private data class Row(
        val text: String,
        val sourceText: String,
        val confidence: Float?,
        val uncertain: Boolean,
        val page: Int,
        val box: OcrBox?,
    )

    private const val MAX_ROW_MERGES = 6
    private const val MAX_ROW_LENGTH = 500
    private const val ROW_OVERLAP = 0.6

    /**
     * Group lines that sit on the same visual row (vertical overlap of the
     * bounding boxes), preserving left-to-right column order. Rows keep raw
     * provenance: `sourceText` joins the *verbatim* line texts.
     */
    private fun buildRows(lines: List<NormalizedLine>): List<Row> {
        if (lines.isEmpty()) return emptyList()
        val rows = ArrayList<Row>(lines.size)
        // Group per page, ordered top-to-bottom when boxes exist.
        for (page in lines.map { it.page }.distinct()) {
            val pageLines = lines.filter { it.page == page }
            val ordered = pageLines.sortedBy { it.box?.top ?: Float.MAX_VALUE }
            var current: MutableList<NormalizedLine>? = null
            fun flush() {
                current?.let { rows.add(toRow(it)) }
                current = null
            }
            for (line in ordered) {
                val box = line.box
                val cur = current
                if (box != null && cur != null && cur.size < MAX_ROW_MERGES) {
                    val lastBox = cur.lastOrNull()?.box
                    if (lastBox != null &&
                        lastBox.verticalOverlapRatio(box) >= ROW_OVERLAP &&
                        joinedLength(cur) + line.text.length <= MAX_ROW_LENGTH
                    ) {
                        cur.add(line)
                        continue
                    }
                }
                flush()
                if (box == null) {
                    // No geometry → the line is its own row.
                    rows.add(toRow(mutableListOf(line)))
                } else {
                    current = mutableListOf(line)
                }
            }
            flush()
        }
        return rows
    }

    private fun joinedLength(row: List<NormalizedLine>): Int = row.sumOf { it.text.length + 2 }

    private fun toRow(parts: List<NormalizedLine>): Row {
        val ordered = if (parts.all { it.box != null } && parts.size > 1) {
            parts.sortedBy { it.box!!.left }
        } else {
            parts
        }
        return Row(
            text = ordered.joinToString("  ") { it.text },
            sourceText = ordered.joinToString(" ") { it.raw },
            confidence = ordered.mapNotNull { it.confidence }.minOrNull(),
            uncertain = ordered.any { it.uncertain },
            page = ordered.first().page,
            box = ordered.mapNotNull { it.box }.reduceOrNull { a, b -> a.union(b) },
        )
    }

    // --- classification ---------------------------------------------------------------

    private fun buildFact(
        documentId: String,
        row: Row,
        ids: IdGenerator,
    ): MedicalFact? {
        val text = row.text
        val sourceText = row.sourceText

        patientInfo(text)?.let { (name, value) ->
            return fact(documentId, ids, FactType.PATIENT_INFO, name, value, sourceText,
                combined(0.9, row))
        }

        allergy(text)?.let { name ->
            return fact(documentId, ids, FactType.ALLERGY, name, name, sourceText,
                combined(0.9, row))
        }

        lab(text)?.let { lab ->
            val value = lab.range?.let { "${lab.value} (reference $it)" } ?: lab.value
            return fact(documentId, ids, FactType.LAB_RESULT, lab.name, value, sourceText,
                combined(0.85, row), unit = lab.unit, referenceRange = lab.range)
        }

        medication(text)?.let { m ->
            val value = (listOfNotNull(m.name) +
                listOfNotNull(m.dose, m.frequency, m.timing, m.duration))
                .joinToString(", ")
                .ifBlank { text }
            val garbledName = m.name != null && nameLooksGarbled(m.name)
            return fact(documentId, ids, FactType.MEDICATION, m.name, value, sourceText,
                combined(m.confidence, row, extraUncertain = garbledName))
                .copy(
                    dose = m.dose,
                    frequency = m.frequency,
                    timing = m.timing,
                    duration = m.duration,
                    route = m.route,
                    instructions = m.instructions,
                )
        }

        condition(text)?.let { name ->
            return fact(documentId, ids, FactType.CONDITION, name, name, sourceText,
                combined(0.8, row))
        }

        if (warningRegex.containsMatchIn(text)) {
            return fact(documentId, ids, FactType.WARNING_SIGNS, null, text, sourceText,
                combined(0.8, row))
        }

        if (followUpRegex.containsMatchIn(text)) {
            return fact(documentId, ids, FactType.FOLLOW_UP, null, text, sourceText,
                combined(0.8, row))
        }

        procedureRegex.find(text)?.let { match ->
            val name = clean(match.groupValues[1])
            if (name.isNotBlank()) {
                return fact(documentId, ids, FactType.PROCEDURE, name, name, sourceText,
                    combined(0.8, row))
            }
        }

        if (instructionRegex.containsMatchIn(text)) {
            return fact(documentId, ids, FactType.INSTRUCTION, null, text, sourceText,
                combined(0.7, row))
        }

        return null
    }

    /**
     * Pattern score × recognizer confidence. Low OCR confidence or a
     * normalizer-corrected line lowers the result so the item is presented
     * as "needs verification"; it never becomes trusted on its own.
     */
    private fun combined(pattern: Double, row: Row, extraUncertain: Boolean = false): Double {
        val ocrFactor = row.confidence
            ?.let { 0.6 + 0.4 * it.coerceIn(0f, 1f).toDouble() }
            ?: 1.0
        var value = pattern * ocrFactor
        if (row.uncertain || extraUncertain) value = minOf(value, 0.55)
        return value.coerceIn(0.05, 1.0)
    }

    /** Digits or operator characters inside a name ⇒ likely OCR damage. */
    private fun nameLooksGarbled(name: String): Boolean {
        if (name.length < 3) return true
        if (name.any { it.isDigit() }) return true
        if (name.any { it in "|~^{}[]<>?\\\"" }) return true
        return false
    }

    // --- individual matchers ---------------------------------------------------------

    private fun patientInfo(text: String): Pair<String, String>? {
        val match = labelRegex.find(text) ?: return null
        val name = match.groupValues[1].trim()
        val value = clean(match.groupValues[2])
        if (value.isBlank()) return null
        return name to value
    }

    private fun allergy(text: String): String? {
        val candidate = allergyToRegex.find(text)?.groupValues?.get(1)
            ?: allergyLabelRegex.find(text)?.groupValues?.get(1)
            ?: return null
        val name = clean(candidate).trimEnd('.', ',', ';')
        if (name.isBlank()) return null
        if (negativeAllergyRegex.containsMatchIn(name)) return null
        return name
    }

    private data class Lab(val name: String, val value: String, val unit: String?, val range: String?)

    private fun lab(text: String): Lab? {
        val medMarkers = hasMedMarkers(text)
        val bp = bpPairRegex.find(text)
        val valueMatch = bp ?: labValueRegex.find(text)
            ?: if (medMarkers) null else percentRegex.find(text)

        if (valueMatch == null) return null
        val before = clean(
            text.substring(0, valueMatch.range.first).trimEnd('-', '–', '—', ':', ','),
        ).trim()
        if (before.isBlank()) return null
        val name = before.split(Regex("""\s+""")).takeLast(5).joinToString(" ")

        val unit = if (bp != null) {
            "mmhg"
        } else {
            valueMatch.groupValues[2].lowercase().ifBlank { "%" }
        }
        // Standalone drug-dose units belong to the medication matcher.
        if (unit in setOf("mg", "mcg", "ug", "µg", "g", "ml", "iu", "units", "unit")) return null

        val value = when {
            bp != null -> "${bp.groupValues[1].trim()} mmHg"
            unit == "%" -> "${valueMatch.groupValues[1]}%"
            else -> "${valueMatch.groupValues[1]} ${valueMatch.groupValues[2]}"
        }
        val range = referenceRangeRegex.find(text)?.groupValues?.get(1)
        return Lab(name = name, value = value, unit = unit, range = range)
    }

    private data class Medication(
        val name: String?,
        val dose: String?,
        val frequency: String?,
        val timing: String?,
        val duration: String?,
        val route: String?,
        val instructions: String?,
        val confidence: Double,
    )

    private fun medication(text: String): Medication? {
        val doseMatch = doseRegex.find(text)
            ?: percentRegex.find(text)?.takeIf { hasMedMarkers(text) }

        var frequency: String? = null
        var frequencyCut: Int? = null
        for (pattern in frequencyPatterns) {
            val match = pattern.regex.find(text) ?: continue
            frequency = when (pattern.label) {
                "EVERY_N_HOURS" -> "Every ${match.groupValues[1]} hours"
                "TIMES_A_DAY" -> "${match.groupValues[1]} times a day"
                else -> pattern.label
            }
            frequencyCut = match.range.first
            break
        }
        val hasForm = formPrefixRegex.containsMatchIn(text)
        val timing = timingPatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(text)?.value?.lowercase()?.replaceFirstChar { it.uppercase() }
        }

        // A line is only a medication if it carries a dose, a frequency or a
        // dosage-form word. "After food" alone is not a medication.
        if (doseMatch == null && frequency == null && !hasForm) return null

        val duration = durationRegex.find(text)?.let { m ->
            if (m.groupValues[1].isBlank()) {
                m.value.replace(Regex("""^\s*for\s+""", RegexOption.IGNORE_CASE), "")
            } else {
                "${m.groupValues[1]} ${m.groupValues[2]}"
            }
        }

        val name = extractName(text, doseMatch?.range?.first ?: frequencyCut, hasForm)

        val confidence = when {
            name != null && doseMatch != null -> 0.9
            name != null && frequency != null -> 0.85
            name != null -> 0.7
            else -> 0.5
        }

        return Medication(
            name = name,
            dose = doseMatch?.let { match ->
                if (match.groups[2]?.value == null) "${match.groupValues[1]}%"
                else "${match.groupValues[1]} ${match.groupValues[2]}"
            },
            frequency = frequency,
            timing = timing,
            duration = duration,
            route = routePatterns.firstNotNullOfOrNull { it.find(text)?.value },
            instructions = instructionsFrom(text, frequency),
            confidence = confidence,
        )
    }

    /** Quantity + standing instruction phrases, verbatim; excludes what frequency already holds. */
    private fun instructionsFrom(text: String, frequency: String?): String? {
        val parts = ArrayList<String>()
        quantityRegex.find(text)?.let { parts += it.value }
        for (pattern in instructionPhrasePatterns) {
            val match = pattern.find(text) ?: continue
            val phrase = match.value
            if (phrase.equals(frequency, ignoreCase = true)) continue
            if (parts.any { it.contains(phrase, ignoreCase = true) }) continue
            parts += phrase
        }
        return parts.distinctBy { it.lowercase() }.joinToString(", ").ifBlank { null }
    }

    private fun extractName(text: String, cutAt: Int?, hasForm: Boolean): String? {
        if (cutAt == null && !hasForm) {
            var stripped = text.replace(stripLeadVerbRegex, "")
            stripped = stripped.trim().trimEnd('-', '–', '—', ':', ',', '.', ';')
            val cleaned = stripped.ifBlank { null } ?: return null
            return cleaned.takeIf { it.length <= 60 }
        }
        if (cutAt == null) {
            // Dosage form but no dose/frequency: "Cream to affected area" → "Cream".
            val form = formPrefixRegex.find(text)?.value ?: return null
            val rest = text.substring(form.length).trim()
            val next = rest.split(" ").firstOrNull()?.lowercase()?.trim(',', '.', ':')
            return if (next != null && next.isNotEmpty() && next !in nameStopWords) {
                "$form ${rest.split(" ").first()}"
            } else {
                form
            }
        }
        var namePart = text.substring(0, cutAt)
        namePart = namePart.replace(stripLeadVerbRegex, "")
            .replace(leadingQuantityRegex, "")
        namePart = namePart.trim().trimEnd('-', '–', '—', ':', ',', '.', ';')
        return namePart.ifBlank { null }
    }

    private fun condition(text: String): String? {
        val fromLabel = conditionLabelRegex.find(text)?.groupValues?.get(1)
            ?: diagnosedWithRegex.find(text)?.groupValues?.get(1)
            ?: knownCaseRegex.find(text)?.groupValues?.get(1)
            ?: return null
        val name = clean(fromLabel).trimEnd('.', ',', ';')
        return name.takeIf { it.isNotBlank() }
    }

    private fun hasMedMarkers(text: String): Boolean =
        formPrefixRegex.containsMatchIn(text) ||
            frequencyPatterns.any { it.regex.containsMatchIn(text) } ||
            instructionRegex.containsMatchIn(text)

    // --- helpers ---------------------------------------------------------------------

    private fun fact(
        documentId: String,
        ids: IdGenerator,
        type: FactType,
        name: String?,
        value: String,
        sourceText: String,
        confidence: Double,
        unit: String? = null,
        referenceRange: String? = null,
    ) = MedicalFact(
        id = ids.newId(),
        documentId = documentId,
        type = type,
        status = FactStatus.PENDING,
        name = name,
        dose = null,
        frequency = null,
        timing = null,
        duration = null,
        value = value.ifBlank { sourceText },
        sourceText = sourceText,
        confidence = confidence,
        unit = unit,
        referenceRange = referenceRange,
    )

    internal fun clean(text: String): String =
        text.replace(leadingNoiseRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
