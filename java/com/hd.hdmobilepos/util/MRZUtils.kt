package com.hd.hdmobilepos.util

import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object MRZUtils {

    /**
     * MRZ 후보.
     * - isStrict=true  : 개인번호/최종 체크디지트까지 포함해 TD3 규격을 "완전" 통과
     * - isStrict=false : 여권번호/생년월일/만료일(업무 핵심 필드)만 체크디지트 통과
     *                   (개인번호/최종CD는 OCR에서 흔히 깨지는 영역이라 Activity에서 안정화 후 사용)
     */
    data class MrzCandidate(
        val line1: String,
        val line2: String,
        val isStrict: Boolean
    )

    // --- TD3 Passport MRZ constants ---
    private const val LINE_LENGTH = 44
    private const val TOTAL_LENGTH = LINE_LENGTH * 2

    // Activity에서 row(라인) 리스트를 넘겨줄 때, 하단/ROI 위주로 이미 걸러져도
    // 가까이 촬영 시 라인이 늘어날 수 있어서 window는 조금 여유있게.
    private const val MAX_ROWS_WINDOW = 12

    // patterns
    private val SPACE_PATTERN: Pattern = Pattern.compile("\\s+")
    private val INVALID_CHAR_PATTERN: Pattern = Pattern.compile("[^A-Z0-9<]")
    private val ANGLE_QUOTES_PATTERN: Pattern = Pattern.compile("[«»∞∝]")

    // TD3 여권 MRZ 1행은 대부분 P< 로 시작
    private const val FIRST_LINE_REGEX = "P[0-9A-Z<][0-9A-Z<]{3}[0-9A-Z<]{39}"
    private val FIRST_LINE_PATTERN: Regex = Regex(FIRST_LINE_REGEX)

    // 숫자 필드에서 흔한 OCR 오인식 보정: O/Q/D->0, I/L->1, Z->2, S->5, G->6, B->8
    private val DIGIT_FIX_MAP: Map<Char, Char> = mapOf(
        'O' to '0',
        'Q' to '0',
        'D' to '0',
        'I' to '1',
        'L' to '1',
        'Z' to '2',
        'S' to '5',
        'G' to '6',
        'B' to '8'
    )

    private val COUNTRY_ALPHA_FIX_MAP: Map<Char, Char> = mapOf(
        '0' to 'O',
        '1' to 'I',
        '2' to 'Z',
        '5' to 'S',
        '6' to 'G',
        '8' to 'B'
    )

    private val ICAO_COUNTRY_CODES: Set<String> by lazy {
        buildSet {
            Locale.getISOCountries().forEach { alpha2 ->
                try {
                    add(Locale("", alpha2).isO3Country.uppercase(Locale.ROOT))
                } catch (_: Exception) {
                    // ignore invalid locale mapping
                }
            }
            // MRZ/ICAO에서 ISO 외 특수 코드가 섞여 들어오는 경우도 최소한 허용합니다.
            addAll(setOf("D<<", "GBD", "GBN", "GBO", "GBP", "GBS", "UNO", "UNA", "UNK"))
        }
    }

    // --- Public APIs ---

    /**
     * (호환용) OCR 라인 리스트에서 MRZ 2줄(44자 x 2)을 추출합니다.
     * - 내부적으로 체크디지트 검증까지 통과한 경우만 반환합니다.
     */
    @JvmStatic
    fun extractMRZFromLines(ocrLines: List<String>?): Array<String> {
        val fixed = findValidTd3PassportMrzFromRows(ocrLines) ?: return emptyArray()
        return arrayOf(fixed.first, fixed.second)
    }

    /**
     * rows(라인) 기반으로 TD3 여권 MRZ를 찾습니다.
     * - 가까이 촬영 시: MRZ 1줄이 다른 문자열과 "붙어서" 길어져도, 44자 substring 후보를 뽑아 검증합니다.
     * - 체크디지트 검증이 핵심 필터라서 "이상한 문자열이 섞인" 후보는 대부분 탈락합니다.
     *
     * @return 유효한 MRZ(line1,line2) or null
     */
    @JvmStatic
    fun findValidTd3PassportMrzFromRows(rows: List<String>?): Pair<String, String>? {
        if (rows.isNullOrEmpty()) return null

        // 1) 기본 클린
        val cleaned = rows
            .asSequence()
            .map { SPACE_PATTERN.matcher(it ?: "").replaceAll("").uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toList()

        if (cleaned.isEmpty()) return null

        // 2) 하단 쪽만 우선
        val window = if (cleaned.size > MAX_ROWS_WINDOW) cleaned.takeLast(MAX_ROWS_WINDOW) else cleaned
        val line1CandidatesByRow = window.map { candidatesFromRow(it, isLine1 = true) }
        val line2CandidatesByRow = window.map { candidatesFromRow(it, isLine1 = false) }

        // 3) (주로) line1(row i) + line2(row j) 형태를 탐색 (MRZ는 아래쪽이므로 bottom-up)
        for (i in window.lastIndex downTo 0) {
            val c1 = line1CandidatesByRow[i]
            if (c1.isEmpty()) continue

            val maxJ = min(window.lastIndex, i + 3) // 가끔 빈 줄/노이즈 한 줄이 끼는 경우 대비
            for (j in (i + 1)..maxJ) {
                if (j > window.lastIndex) break
                val c2 = line2CandidatesByRow[j]
                if (c2.isEmpty()) continue

                for (l1 in c1) {
                    for (l2 in c2) {
                        val fixed = validateAndFixTd3PassportMrz(l1, l2)
                        if (fixed != null) return fixed
                    }
                }
            }
        }

        // 4) (드물게) 두 줄이 한 row로 붙어 들어온 경우도 탐색
        for (row in window.asReversed()) {
            val s = row
            if (s.length < TOTAL_LENGTH) continue
            var added = 0
            val maxStart = s.length - TOTAL_LENGTH
            for (st in 0..maxStart) {
                if (s[st] != 'P') continue
                val l1 = s.substring(st, st + LINE_LENGTH)
                val l2 = s.substring(st + LINE_LENGTH, st + TOTAL_LENGTH)
                val fixed = validateAndFixTd3PassportMrz(l1, l2)
                if (fixed != null) return fixed
                added++
                if (added >= 6) break
            }
        }

        return null
    }

    /**
     * rows(라인) 기반으로 "가장 좋은" TD3 여권 MRZ 후보를 찾습니다.
     *
     * 우선순위:
     * 1) strict(개인번호/최종 체크디지트 포함) 통과 → 즉시 반환
     * 2) core(여권번호/생년월일/만료일)만 통과 → 후보 반환 (Activity에서 2프레임 안정화 후 성공 처리)
     */
    @JvmStatic
    fun findBestTd3PassportMrzCandidateFromRows(rows: List<String>?): MrzCandidate? {
        if (rows.isNullOrEmpty()) return null

        // 1) 기본 클린
        val cleaned = rows
            .asSequence()
            .map { SPACE_PATTERN.matcher(it ?: "").replaceAll("").uppercase(Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toList()

        if (cleaned.isEmpty()) return null

        val window = if (cleaned.size > MAX_ROWS_WINDOW) cleaned.takeLast(MAX_ROWS_WINDOW) else cleaned
        val line1CandidatesByRow = window.map { candidatesFromRow(it, isLine1 = true) }
        val line2CandidatesByRow = window.map { candidatesFromRow(it, isLine1 = false) }

        var bestCore: Pair<String, String>? = null

        // 2) (주로) line1(row i) + line2(row j) 탐색
        for (i in window.lastIndex downTo 0) {
            val c1 = line1CandidatesByRow[i]
            if (c1.isEmpty()) continue

            val maxJ = min(window.lastIndex, i + 3)
            for (j in (i + 1)..maxJ) {
                if (j > window.lastIndex) break
                val c2 = line2CandidatesByRow[j]
                if (c2.isEmpty()) continue

                for (l1 in c1) {
                    for (l2 in c2) {
                        val strict = validateAndFixTd3PassportMrz(l1, l2)
                        if (strict != null) return MrzCandidate(strict.first, strict.second, true)

                        if (bestCore == null) {
                            val core = validateAndFixTd3PassportMrzCore(l1, l2)
                            if (core != null) bestCore = core
                        }
                    }
                }
            }
        }

        // 3) (드물게) 두 줄이 한 row로 붙어 들어온 경우도 탐색
        for (row in window.asReversed()) {
            val s = row
            if (s.length < TOTAL_LENGTH) continue
            var added = 0
            val maxStart = s.length - TOTAL_LENGTH
            for (st in 0..maxStart) {
                if (s[st] != 'P') continue
                val l1 = s.substring(st, st + LINE_LENGTH)
                val l2 = s.substring(st + LINE_LENGTH, st + TOTAL_LENGTH)

                val strict = validateAndFixTd3PassportMrz(l1, l2)
                if (strict != null) return MrzCandidate(strict.first, strict.second, true)

                if (bestCore == null) {
                    val core = validateAndFixTd3PassportMrzCore(l1, l2)
                    if (core != null) bestCore = core
                }

                added++
                if (added >= 6) break
            }
        }

        return bestCore?.let { MrzCandidate(it.first, it.second, false) }
    }

    @JvmStatic
    fun normalizeMrzChars(line: String?, isLine1: Boolean): String {
        return if (isLine1) normalizeLine1(line) else normalizeLine2(line)
    }

    /**
     * (간단) TD3 여권 MRZ처럼 보이는지 체크
     * - 엄격 검증(validateTd3PassportMrzStrict) 기반
     */
    @JvmStatic
    fun lookslikePassportMRZ(line1: String?, line2: String?): Boolean {
        if (line1.isNullOrBlank() || line2.isNullOrBlank()) return false
        if (line1.length != LINE_LENGTH || line2.length != LINE_LENGTH) return false
        val l1 = normalizeLine1(line1)
        val l2 = fixWholeLine(normalizeLine2(line2))
        return validateTd3PassportMrzStrict(l1, l2)
    }

    /**
     * TD3 여권 MRZ(2줄 44자) 유효성 검증(체크디지트) + 보정.
     *
     * @return 유효하면 (line1, line2) 반환. 실패하면 null.
     */
    @JvmStatic
    fun validateAndFixTd3PassportMrz(line1: String?, line2: String?): Pair<String, String>? {
        if (line1.isNullOrBlank() || line2.isNullOrBlank()) return null
        if (line1.length != LINE_LENGTH || line2.length != LINE_LENGTH) return null

        val l1 = normalizeLine1(line1)
        val baseL2 = fixWholeLine(normalizeLine2(line2))

        // 후보 1) 기본 보정만
        if (validateTd3PassportMrzStrict(l1, baseL2)) return Pair(l1, baseL2)

        // 후보 2) 숫자 필드(여권번호/날짜/체크디지트)까지 보정
        val repairedL2 = repairDigitsInTd3Line2(baseL2, aggressivePassportNo = true, repairPersonalNumber = false)
        if (validateTd3PassportMrzStrict(l1, repairedL2)) return Pair(l1, repairedL2)

        // 후보 3) 날짜/체크디지트만 보정
        val repairedL2DatesOnly = repairDigitsInTd3Line2(baseL2, aggressivePassportNo = false, repairPersonalNumber = false)
        if (validateTd3PassportMrzStrict(l1, repairedL2DatesOnly)) return Pair(l1, repairedL2DatesOnly)

        // 후보 4) personal number(28..41)까지 보정(가까이 촬영 시 도움이 되는 케이스가 있음)
        val repairedL2WithPersonal = repairDigitsInTd3Line2(baseL2, aggressivePassportNo = true, repairPersonalNumber = true)
        if (validateTd3PassportMrzStrict(l1, repairedL2WithPersonal)) return Pair(l1, repairedL2WithPersonal)

        return null
    }

    /**
     * TD3 여권 MRZ "핵심 필드"(업무에 꼭 필요한 필드)만 검증합니다.
     *
     * - 여권번호 + 체크디지트
     * - 생년월일 + 체크디지트
     * - 만료일 + 체크디지트
     * - 발행국/국적/성별 포맷(문자/허용값)
     *
     * 개인번호(28..41), personal CD(42), 최종 CD(43)는 가까이 촬영/노이즈에서 자주 깨져
     * strict 검증만 고집하면 "화면에 크게 들어와도" 인식이 늦어지는 원인이 됩니다.
     */
    @JvmStatic
    fun validateAndFixTd3PassportMrzCore(line1: String?, line2: String?): Pair<String, String>? {
        if (line1.isNullOrBlank() || line2.isNullOrBlank()) return null
        if (line1.length != LINE_LENGTH || line2.length != LINE_LENGTH) return null

        val l1 = normalizeLine1(line1)
        val baseL2 = fixWholeLine(normalizeLine2(line2))

        // 숫자 필드는 적극 보정(여권번호/날짜/체크디지트)
        val repairedL2 = repairDigitsInTd3Line2(baseL2, aggressivePassportNo = true, repairPersonalNumber = false)

        return if (validateTd3PassportMrzCoreInternal(l1, repairedL2)) {
            Pair(l1, repairedL2)
        } else {
            null
        }
    }

    // --- Candidate generation (row -> 44char candidates) ---

    private fun candidatesFromRow(row: String, isLine1: Boolean): List<String> {
        if (row.isBlank()) return emptyList()

        val s = SPACE_PATTERN.matcher(row).replaceAll("").uppercase(Locale.ROOT)
        if (s.length < LINE_LENGTH) return emptyList()

        val maxStart = s.length - LINE_LENGTH
        if (maxStart == 0) {
            val only = if (isLine1) normalizeLine1(s) else normalizeLine2(s)
            return if (isLine1 && (!FIRST_LINE_PATTERN.matches(only) || !only.substring(5).contains("<<"))) emptyList() else listOf(only)
        }

        return if (isLine1) {
            // line1은 'P'로 시작하는 모든 위치에서 44자 후보를 만들고 regex로 필터링합니다.
            // (P<, PM, P0 등 다양한 문서코드 2번째 문자를 허용)
            val top = ArrayList<Pair<String, Int>>(8)

            for (i in 0..maxStart) {
                if (s[i] != 'P') continue
                val sub = s.substring(i, i + LINE_LENGTH)
                val cand = normalizeLine1(sub)

                // 이름 구간(5..)에 '<<' 패턴이 없으면 MRZ 1행일 가능성이 낮습니다.
                if (!cand.substring(5).contains("<<")) continue

                if (!FIRST_LINE_PATTERN.matches(cand)) continue

                val rough = scoreLine1Rough(cand)
                if (rough < 10) continue

                if (top.size < 8) {
                    top.add(cand to rough)
                    continue
                }

                var minIdx = 0
                var minScore = top[0].second
                for (k in 1 until top.size) {
                    if (top[k].second < minScore) {
                        minScore = top[k].second
                        minIdx = k
                    }
                }
                if (rough > minScore) {
                    top[minIdx] = cand to rough
                }
            }

            top
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
        } else {
            // line2는 숫자 비중이 높으므로 rough score로 상위 후보만 선택
            val top = ArrayList<Pair<String, Int>>(10)

            for (i in 0..maxStart) {
                val sub = s.substring(i, i + LINE_LENGTH)

                // 빠른 필터(속도): 생년월일/만료일/체크디지트 위치가 "숫자(또는 숫자로 보정 가능)"이어야 합니다.
                if (!looksLikeLine2Quick(sub)) continue

                val cand = normalizeLine2(sub)
                // 너무 MRZ스럽지 않으면 skip (속도 + 오탐 방지)
                val rough = scoreLine2Rough(cand)
                if (rough < 12) continue

                if (top.size < 10) {
                    top.add(cand to rough)
                    continue
                }

                var minIdx = 0
                var minScore = top[0].second
                for (k in 1 until top.size) {
                    if (top[k].second < minScore) {
                        minScore = top[k].second
                        minIdx = k
                    }
                }
                if (rough > minScore) {
                    top[minIdx] = cand to rough
                }
            }

            top
                .sortedByDescending { it.second }
                .map { it.first }
                .distinct()
        }
    }

    private fun looksLikeLine2Quick(raw44: String): Boolean {
        if (raw44.length != LINE_LENGTH) return false
        fun isDigitish(c: Char): Boolean = (c in '0'..'9') || DIGIT_FIX_MAP.containsKey(c)

        // date YYMMDD (13..18), expiry YYMMDD (21..26)
        for (i in 13..18) if (!isDigitish(raw44[i])) return false
        for (i in 21..26) if (!isDigitish(raw44[i])) return false

        // check digits (9,19,27)
        for (i in intArrayOf(9, 19, 27)) if (!isDigitish(raw44[i])) return false

        return true
    }

    private fun scoreLine2Rough(line2: String): Int {
        if (line2.length != LINE_LENGTH) return 0
        var score = 0

        val digits = line2.count { it in '0'..'9' }
        val angles = line2.count { it == '<' }
        score += digits * 2
        score += angles / 2

        // 체크디지트 위치는 숫자여야 함
        // - 핵심(9,19,27)은 강하게 가산/감산
        // - personal/final(42,43)은 OCR에서 자주 깨지므로 약하게만 반영
        for (p in intArrayOf(9, 19, 27)) {
            score += if (line2[p] in '0'..'9') 8 else -12
        }
        for (p in intArrayOf(42, 43)) {
            score += if (line2[p] in '0'..'9') 2 else -2
        }

        // 생년월일/만료일 영역(YYMMDD) 숫자 가산점
        for (i in 13..18) if (line2[i] in '0'..'9') score += 1 else score -= 1
        for (i in 21..26) if (line2[i] in '0'..'9') score += 1 else score -= 1

        return score
    }

    private fun scoreLine1Rough(line1: String): Int {
        if (line1.length != LINE_LENGTH) return 0
        if (!FIRST_LINE_PATTERN.matches(line1)) return 0

        val nameField = line1.substring(5)
        val separatorIdx = nameField.indexOf("<<")
        if (separatorIdx < 0) return 0

        var score = 0
        score += 10 // line1 후보 생성까지 왔으면 기본 MRZ shape는 맞는 편

        val fillerCount = nameField.count { it == '<' }
        val alphaCount = nameField.count { it in 'A'..'Z' }
        score += min(10, fillerCount)
        if (alphaCount in 5..30) score += 4

        // surname << given-name separator는 이름 필드 초중반에 있는 경우가 대부분 자연스럽습니다.
        if (separatorIdx in 1..28) score += 6 else score -= 4

        val trailingRun = trailingRunLength(nameField, '<')
        if (trailingRun >= 4) score += min(8, trailingRun)

        val suspiciousK = countSuspiciousNameFieldKs(nameField)
        score -= suspiciousK * 5

        return score
    }

    private fun scoreLine1Rough(line1: String): Int {
        if (line1.length != LINE_LENGTH) return 0
        if (!FIRST_LINE_PATTERN.matches(line1)) return 0

        val nameField = line1.substring(5)
        val separatorIdx = nameField.indexOf("<<")
        if (separatorIdx < 0) return 0

        var score = 0
        score += 10 // line1 후보 생성까지 왔으면 기본 MRZ shape는 맞는 편

        val fillerCount = nameField.count { it == '<' }
        val alphaCount = nameField.count { it in 'A'..'Z' }
        score += min(10, fillerCount)
        if (alphaCount in 5..30) score += 4

        // surname << given-name separator는 이름 필드 초중반에 있는 경우가 대부분 자연스럽습니다.
        if (separatorIdx in 1..28) score += 6 else score -= 4

        val trailingRun = trailingRunLength(nameField, '<')
        if (trailingRun >= 4) score += min(8, trailingRun)

        val suspiciousK = countSuspiciousNameFieldKs(nameField)
        score -= suspiciousK * 5

        return score
    }

    private fun scoreLine1Rough(line1: String): Int {
        if (line1.length != LINE_LENGTH) return 0
        if (!FIRST_LINE_PATTERN.matches(line1)) return 0

        val nameField = line1.substring(5)
        val separatorIdx = nameField.indexOf("<<")
        if (separatorIdx < 0) return 0

        var score = 0
        score += 10 // line1 후보 생성까지 왔으면 기본 MRZ shape는 맞는 편

        val fillerCount = nameField.count { it == '<' }
        val alphaCount = nameField.count { it in 'A'..'Z' }
        score += min(10, fillerCount)
        if (alphaCount in 5..30) score += 4

        // surname << given-name separator는 이름 필드 초중반에 있는 경우가 대부분 자연스럽습니다.
        if (separatorIdx in 1..28) score += 6 else score -= 4

        val trailingRun = trailingRunLength(nameField, '<')
        if (trailingRun >= 4) score += min(8, trailingRun)

        val suspiciousK = countSuspiciousNameFieldKs(nameField)
        score -= suspiciousK * 5

        return score
    }

    // --- Normalization helpers ---

    private fun normalizeLine1(line: String?): String {
        if (line.isNullOrEmpty()) return ""
        val base = baseNormalize(line)

        // line1은 문자 위주라 숫자→문자 오인식 보정이 도움이 됨
        val arr = base.toCharArray()
        for (i in arr.indices) {
            // TD3 1행의 2번째 문자는 문서코드(예: '<', 'M', '0' 등)라서 숫자→문자 치환을 하지 않습니다.
            if (i == 1) continue

            when (arr[i]) {
                '0' -> arr[i] = 'O'
                '1' -> arr[i] = 'I'
                '5' -> arr[i] = 'S'
                '8' -> arr[i] = 'B'
            }
        }

        var fixed = fixKAsAngle(String(arr))
        fixed = repairCountryCodeRegion(fixed, 2)
        fixed = repairNameFieldFillers(fixed)
        return sanitizeTrailingFiller(fixed)
    }

    private fun normalizeLine2(line: String?): String {
        if (line.isNullOrEmpty()) return ""
        val base = baseNormalize(line)
        return repairCountryCodeRegion(fixKAsAngle(base), 10)
    }

    private fun baseNormalize(line: String): String {
        // 특수 문자 → '<', 공백 제거, 대문자화
        val noSpace = SPACE_PATTERN.matcher(line).replaceAll("")
        val angled = ANGLE_QUOTES_PATTERN.matcher(noSpace).replaceAll("<")
        val upper = angled.uppercase(Locale.ROOT)
        // 허용 문자 외는 '<'
        return INVALID_CHAR_PATTERN.matcher(upper).replaceAll("<")
    }

    private fun repairCountryCodeRegion(line: String, start: Int): String {
        if (line.length < start + 3) return line
        val repaired = normalizeCountryCodeToken(line.substring(start, start + 3))
        if (repaired == null) return line

        val arr = line.toCharArray()
        repaired.forEachIndexed { idx, c -> arr[start + idx] = c }
        return String(arr)
    }

    private fun normalizeCountryCodeToken(raw: String): String? {
        if (raw.length != 3) return null

        val options = raw.map { ch ->
            linkedSetOf(ch, COUNTRY_ALPHA_FIX_MAP[ch] ?: ch)
        }

        var fallback = String(CharArray(3) { idx -> options[idx].first() })
        for (a in options[0]) {
            for (b in options[1]) {
                for (c in options[2]) {
                    val candidate = charArrayOf(a, b, c).concatToString()
                    if (candidate in ICAO_COUNTRY_CODES) return candidate
                    fallback = candidate
                }
            }
        }

        return fallback
    }

    /**
     * OCR에서 '<'가 'K'로 자주 오인식되는 기기가 있습니다.
     * - 실제 데이터의 'K'(예: KIM)까지 날리지 않기 위해,
     *   'K'가 주변이 filler(<)처럼 보일 때만 '<'로 바꿉니다.
     */
    private fun fixKAsAngle(s: String): String {
        if (!s.contains('K')) return s
        val arr = s.toCharArray()
        for (i in arr.indices) {
            if (arr[i] != 'K') continue
            val prev = if (i > 0) arr[i - 1] else '<'
            val next = if (i < arr.lastIndex) arr[i + 1] else '<'
            val looksLikeFiller = (prev == '<' || prev == 'K') && (next == '<' || next == 'K')
            if (looksLikeFiller || hasStrongFillerNeighborhood(arr, i)) arr[i] = '<'
        }
        return String(arr)
    }

    private fun hasStrongFillerNeighborhood(arr: CharArray, idx: Int): Boolean {
        // '<' run 근처에서 끼어든 K(예: <<K<<, <K<<<)를 filler로 보정합니다.
        // 실제 이름(KIM, PARK)은 주변 filler 밀도가 낮아 대부분 유지됩니다.
        var fillerCount = 0
        for (offset in -2..2) {
            if (offset == 0) continue
            val p = idx + offset
            if (p !in arr.indices) {
                fillerCount++
                continue
            }
            if (arr[p] == '<' || arr[p] == 'K') fillerCount++
        }
        return fillerCount >= 3
    }

    /**
     * line1 이름 영역(5..43)에서 filler('<')가 K로 오인식된 흔적을 구조적으로 보정합니다.
     * - 이름 토큰 내부의 실제 K(KIM, PARK)는 최대한 유지
     * - separator/공백 filler 경계의 K(<<K, K<<, <K<, filler-run 내부)는 적극적으로 '<'로 치환
     */
    private fun repairNameFieldFillers(line1: String): String {
        if (line1.length != LINE_LENGTH) return line1
        if (!line1.contains('K')) return line1

        val arr = line1.toCharArray()
        for (i in 5 until arr.size) {
            if (arr[i] != 'K') continue
            if (shouldTreatNameFieldKAsFiller(arr, i)) arr[i] = '<'
        }
        return String(arr)
    }

    private fun shouldTreatNameFieldKAsFiller(arr: CharArray, idx: Int): Boolean {
        val prev = charAtOrFiller(arr, idx - 1)
        val next = charAtOrFiller(arr, idx + 1)
        val prev2 = charAtOrFiller(arr, idx - 2)
        val next2 = charAtOrFiller(arr, idx + 2)

        val leftIsLetter = prev in 'A'..'Z' && prev != '<'
        val rightIsLetter = next in 'A'..'Z' && next != '<'

        // 실제 이름 토큰 내부의 K(KIM, PARK 등)는 살립니다.
        if (leftIsLetter && rightIsLetter) return false

        val immediateFiller = prev == '<' || next == '<'
        val nearFiller = prev2 == '<' || next2 == '<'
        val strongFillerRun = countNearbyFillers(arr, idx, radius = 3) >= 3

        if ((prev == '<' && next == '<') || (immediateFiller && nearFiller)) return true
        if (strongFillerRun && (!leftIsLetter || !rightIsLetter)) return true

        // trailing filler 구간에 한 글자만 튀는 패턴(<<<<K<<<<) 보정
        if (!leftIsLetter && !rightIsLetter && (prev2 == '<' || next2 == '<')) return true

        return false
    }

    private fun charAtOrFiller(arr: CharArray, idx: Int): Char =
        if (idx in arr.indices) arr[idx] else '<'

    private fun countNearbyFillers(arr: CharArray, idx: Int, radius: Int): Int {
        var count = 0
        for (offset in -radius..radius) {
            if (offset == 0) continue
            val p = idx + offset
            if (p !in arr.indices || arr[p] == '<') count++
        }
        return count
    }

    private fun countSuspiciousNameFieldKs(nameField: String): Int {
        var suspicious = 0
        for (i in nameField.indices) {
            if (nameField[i] != 'K') continue
            val prev = if (i > 0) nameField[i - 1] else '<'
            val next = if (i < nameField.lastIndex) nameField[i + 1] else '<'
            val prev2 = if (i > 1) nameField[i - 2] else '<'
            val next2 = if (i < nameField.lastIndex - 1) nameField[i + 2] else '<'
            val leftLetter = prev in 'A'..'Z' && prev != '<'
            val rightLetter = next in 'A'..'Z' && next != '<'
            if (leftLetter && rightLetter) continue
            if ((prev == '<' && next == '<') || ((prev == '<' || next == '<') && (prev2 == '<' || next2 == '<'))) {
                suspicious++
            }
        }
        return suspicious
    }

    private fun trailingRunLength(value: String, ch: Char): Int {
        var count = 0
        for (i in value.lastIndex downTo 0) {
            if (value[i] != ch) break
            count++
        }
        return count
    }

    /**
     * line1 이름 영역(5..43)에서 filler('<')가 K로 오인식된 흔적을 구조적으로 보정합니다.
     * - 이름 토큰 내부의 실제 K(KIM, PARK)는 최대한 유지
     * - separator/공백 filler 경계의 K(<<K, K<<, <K<, filler-run 내부)는 적극적으로 '<'로 치환
     */
    private fun repairNameFieldFillers(line1: String): String {
        if (line1.length != LINE_LENGTH) return line1
        if (!line1.contains('K')) return line1

        val arr = line1.toCharArray()
        for (i in 5 until arr.size) {
            if (arr[i] != 'K') continue
            if (shouldTreatNameFieldKAsFiller(arr, i)) arr[i] = '<'
        }
        return String(arr)
    }

    private fun shouldTreatNameFieldKAsFiller(arr: CharArray, idx: Int): Boolean {
        val prev = charAtOrFiller(arr, idx - 1)
        val next = charAtOrFiller(arr, idx + 1)
        val prev2 = charAtOrFiller(arr, idx - 2)
        val next2 = charAtOrFiller(arr, idx + 2)

        val leftIsLetter = prev in 'A'..'Z' && prev != '<'
        val rightIsLetter = next in 'A'..'Z' && next != '<'

        // 실제 이름 토큰 내부의 K(KIM, PARK 등)는 살립니다.
        if (leftIsLetter && rightIsLetter) return false

        val immediateFiller = prev == '<' || next == '<'
        val nearFiller = prev2 == '<' || next2 == '<'
        val strongFillerRun = countNearbyFillers(arr, idx, radius = 3) >= 3

        if ((prev == '<' && next == '<') || (immediateFiller && nearFiller)) return true
        if (strongFillerRun && (!leftIsLetter || !rightIsLetter)) return true

        // trailing filler 구간에 한 글자만 튀는 패턴(<<<<K<<<<) 보정
        if (!leftIsLetter && !rightIsLetter && (prev2 == '<' || next2 == '<')) return true

        return false
    }

    private fun charAtOrFiller(arr: CharArray, idx: Int): Char =
        if (idx in arr.indices) arr[idx] else '<'

    private fun countNearbyFillers(arr: CharArray, idx: Int, radius: Int): Int {
        var count = 0
        for (offset in -radius..radius) {
            if (offset == 0) continue
            val p = idx + offset
            if (p !in arr.indices || arr[p] == '<') count++
        }
        return count
    }

    private fun countSuspiciousNameFieldKs(nameField: String): Int {
        var suspicious = 0
        for (i in nameField.indices) {
            if (nameField[i] != 'K') continue
            val prev = if (i > 0) nameField[i - 1] else '<'
            val next = if (i < nameField.lastIndex) nameField[i + 1] else '<'
            val prev2 = if (i > 1) nameField[i - 2] else '<'
            val next2 = if (i < nameField.lastIndex - 1) nameField[i + 2] else '<'
            val leftLetter = prev in 'A'..'Z' && prev != '<'
            val rightLetter = next in 'A'..'Z' && next != '<'
            if (leftLetter && rightLetter) continue
            if ((prev == '<' && next == '<') || ((prev == '<' || next == '<') && (prev2 == '<' || next2 == '<'))) {
                suspicious++
            }
        }
        return suspicious
    }

    private fun trailingRunLength(value: String, ch: Char): Int {
        var count = 0
        for (i in value.lastIndex downTo 0) {
            if (value[i] != ch) break
            count++
        }
        return count
    }

    /**
     * line1 이름 영역(5..43)에서 filler('<')가 K로 오인식된 흔적을 구조적으로 보정합니다.
     * - 이름 토큰 내부의 실제 K(KIM, PARK)는 최대한 유지
     * - separator/공백 filler 경계의 K(<<K, K<<, <K<, filler-run 내부)는 적극적으로 '<'로 치환
     */
    private fun repairNameFieldFillers(line1: String): String {
        if (line1.length != LINE_LENGTH) return line1
        if (!line1.contains('K')) return line1

        val arr = line1.toCharArray()
        for (i in 5 until arr.size) {
            if (arr[i] != 'K') continue
            if (shouldTreatNameFieldKAsFiller(arr, i)) arr[i] = '<'
        }
        return String(arr)
    }

    private fun shouldTreatNameFieldKAsFiller(arr: CharArray, idx: Int): Boolean {
        val prev = charAtOrFiller(arr, idx - 1)
        val next = charAtOrFiller(arr, idx + 1)
        val prev2 = charAtOrFiller(arr, idx - 2)
        val next2 = charAtOrFiller(arr, idx + 2)

        val leftIsLetter = prev in 'A'..'Z' && prev != '<'
        val rightIsLetter = next in 'A'..'Z' && next != '<'

        // 실제 이름 토큰 내부의 K(KIM, PARK 등)는 살립니다.
        if (leftIsLetter && rightIsLetter) return false

        val immediateFiller = prev == '<' || next == '<'
        val nearFiller = prev2 == '<' || next2 == '<'
        val strongFillerRun = countNearbyFillers(arr, idx, radius = 3) >= 3

        if ((prev == '<' && next == '<') || (immediateFiller && nearFiller)) return true
        if (strongFillerRun && (!leftIsLetter || !rightIsLetter)) return true

        // trailing filler 구간에 한 글자만 튀는 패턴(<<<<K<<<<) 보정
        if (!leftIsLetter && !rightIsLetter && (prev2 == '<' || next2 == '<')) return true

        return false
    }

    private fun charAtOrFiller(arr: CharArray, idx: Int): Char =
        if (idx in arr.indices) arr[idx] else '<'

    private fun countNearbyFillers(arr: CharArray, idx: Int, radius: Int): Int {
        var count = 0
        for (offset in -radius..radius) {
            if (offset == 0) continue
            val p = idx + offset
            if (p !in arr.indices || arr[p] == '<') count++
        }
        return count
    }

    private fun countSuspiciousNameFieldKs(nameField: String): Int {
        var suspicious = 0
        for (i in nameField.indices) {
            if (nameField[i] != 'K') continue
            val prev = if (i > 0) nameField[i - 1] else '<'
            val next = if (i < nameField.lastIndex) nameField[i + 1] else '<'
            val prev2 = if (i > 1) nameField[i - 2] else '<'
            val next2 = if (i < nameField.lastIndex - 1) nameField[i + 2] else '<'
            val leftLetter = prev in 'A'..'Z' && prev != '<'
            val rightLetter = next in 'A'..'Z' && next != '<'
            if (leftLetter && rightLetter) continue
            if ((prev == '<' && next == '<') || ((prev == '<' || next == '<') && (prev2 == '<' || next2 == '<'))) {
                suspicious++
            }
        }
        return suspicious
    }

    private fun trailingRunLength(value: String, ch: Char): Int {
        var count = 0
        for (i in value.lastIndex downTo 0) {
            if (value[i] != ch) break
            count++
        }
        return count
    }

    /**
     * line1에서 trailing filler(연속 '<')가 시작된 이후에는 글자가 나오면 대부분 노이즈입니다.
     * - 가장 마지막에 등장하는 "6개 이상 연속 '<'" run을 filler 시작으로 보고,
     *   그 이후의 A-Z/0-9는 전부 '<'로 치환합니다.
     */
    private fun sanitizeTrailingFiller(line1: String): String {
        val idx = findLastRunStart(line1, '<', minRun = 6)
        if (idx < 0) return line1
        val arr = line1.toCharArray()
        for (i in idx until arr.size) {
            if (arr[i] != '<') arr[i] = '<'
        }
        return String(arr)
    }

    private fun findLastRunStart(s: String, ch: Char, minRun: Int): Int {
        var i = s.lastIndex
        var bestStart = -1
        while (i >= 0) {
            if (s[i] != ch) {
                i--
                continue
            }
            var j = i
            while (j >= 0 && s[j] == ch) j--
            val runStart = j + 1
            val runLen = i - runStart + 1
            if (runLen >= minRun) {
                bestStart = runStart
                break
            }
            i = j
        }
        return bestStart
    }

    // --- Validation helpers (TD3) ---

    /**
     * TD3 여권 MRZ의 "핵심"만 검증합니다.
     * - line1: P<, 발행국 3글자
     * - line2: 여권번호/생년월일/만료일 체크디지트 + 날짜 유효성
     * - nationality/sex 포맷
     */
    private fun validateTd3PassportMrzCoreInternal(line1: String, line2: String): Boolean {
        if (line1.length != LINE_LENGTH || line2.length != LINE_LENGTH) return false
        if (!FIRST_LINE_PATTERN.matches(line1)) return false

        fun alpha3LooksOk(raw: String): Boolean {
            if (raw.length != 3) return false

            val s = normalizeCountryCodeToken(raw) ?: return false
            if (!s.all { it in 'A'..'Z' || it == '<' }) return false
            if (s in ICAO_COUNTRY_CODES) return true

            // OCR에서 한 글자가 '<'로 빠지는 정도는 허용(최소 2글자는 문자)
            return s.count { it in 'A'..'Z' } >= 2
        }

        // issuing country(2..4) : 보통 A-Z 3글자
        val issuing = line1.substring(2, 5)
        if (!alpha3LooksOk(issuing)) return false

        // nationality(10..12) : 보통 A-Z 3글자
        val nat = line2.substring(10, 13)
        if (!alpha3LooksOk(nat)) return false

        // sex(20) : M/F/X/<
        val sex = line2[20]
        if (sex != 'M' && sex != 'F' && sex != 'X' && sex != '<') return false

        // 체크디지트(9,19,27)는 숫자여야 함
        val cdPos = intArrayOf(9, 19, 27)
        for (p in cdPos) {
            val c = line2[p]
            if (c !in '0'..'9') return false
        }

        // 날짜 형식 간단 검증
        val birth = line2.substring(13, 19)
        val expiry = line2.substring(21, 27)
        if (!isValidYYMMDD(birth)) return false
        if (!isValidYYMMDD(expiry)) return false

        val passportNo = line2.substring(0, 9)
        // 여권번호는 너무 짧으면 잘못된 후보일 확률이 큼
        val passportNoCompact = passportNo.replace("<", "")
        if (passportNoCompact.length !in 6..9) return false

        val passportCD = line2[9] - '0'
        val birthCD = line2[19] - '0'
        val expiryCD = line2[27] - '0'

        if (computeCheckDigit(passportNo) != passportCD) return false
        if (computeCheckDigit(birth) != birthCD) return false
        if (computeCheckDigit(expiry) != expiryCD) return false

        return true
    }

    private fun validateTd3PassportMrzStrict(line1: String, line2: String): Boolean {
        // 1) 핵심 필드부터 통과해야 함
        if (!validateTd3PassportMrzCoreInternal(line1, line2)) return false

        // 체크디지트 문자는 숫자여야 함
        val cdPos = intArrayOf(9, 19, 27, 42, 43)
        for (p in cdPos) {
            val c = line2[p]
            if (c !in '0'..'9') return false
        }

        // coreInternal에서 이미 (passport/birth/expiry) 검증 완료
        val passportNo = line2.substring(0, 9)
        val birth = line2.substring(13, 19)
        val expiry = line2.substring(21, 27)

        val personal = line2.substring(28, 42)
        val personalCD = line2[42] - '0'

        val finalCD = line2[43] - '0'

        if (computeCheckDigit(personal) != personalCD) return false

        val composite = line2.substring(0, 10) +
            line2.substring(13, 20) +
            line2.substring(21, 28) +
            line2.substring(28, 43)

        return computeCheckDigit(composite) == finalCD
    }

    private fun isValidYYMMDD(value: String): Boolean {
        if (value.length != 6) return false
        if (!value.all { it in '0'..'9' }) return false
        val mm = value.substring(2, 4).toInt()
        val dd = value.substring(4, 6).toInt()
        if (mm !in 1..12) return false
        if (dd !in 1..31) return false
        return true
    }

    private fun repairDigitsInTd3Line2(
        line2: String,
        aggressivePassportNo: Boolean,
        repairPersonalNumber: Boolean
    ): String {
        val sb = StringBuilder(line2)

        // 체크디지트 위치(숫자)
        val cdPos = intArrayOf(9, 19, 27, 42, 43)
        for (p in cdPos) {
            val fixed = DIGIT_FIX_MAP[sb[p]] ?: sb[p]
            sb.setCharAt(p, fixed)
        }

        // 날짜(숫자)
        for (i in 13..18) {
            val fixed = DIGIT_FIX_MAP[sb[i]] ?: sb[i]
            sb.setCharAt(i, fixed)
        }
        for (i in 21..26) {
            val fixed = DIGIT_FIX_MAP[sb[i]] ?: sb[i]
            sb.setCharAt(i, fixed)
        }

        // (선택) 여권번호(0..8)
        if (aggressivePassportNo) {
            for (i in 0..8) {
                val fixed = DIGIT_FIX_MAP[sb[i]] ?: sb[i]
                sb.setCharAt(i, fixed)
            }
        }

        // (선택) personal number(28..41)
        if (repairPersonalNumber) {
            for (i in 28..41) {
                val fixed = DIGIT_FIX_MAP[sb[i]] ?: sb[i]
                sb.setCharAt(i, fixed)
            }
        }

        return sb.toString()
    }

    /**
     * 라인 전체 보정:
     * - 국적(10~12)엔 0→O 등 (문자 영역)
     * - 생년월일(13~18), 만료일(21~26)엔 O→0 등 (숫자 영역)
     */
    private fun fixWholeLine(s: String): String {
        if (s.length != LINE_LENGTH) return s
        val sb = StringBuilder(s)

        // 국적(문자)
        for (i in 10..12) {
            when (sb[i]) {
                '0' -> sb.setCharAt(i, 'O')
                '1' -> sb.setCharAt(i, 'I')
                '5' -> sb.setCharAt(i, 'S')
                '6' -> sb.setCharAt(i, 'G')
                '8' -> sb.setCharAt(i, 'B')
            }
        }

        val nat = normalizeCountryCodeToken(sb.substring(10, 13))
        if (nat != null) {
            nat.forEachIndexed { idx, c -> sb.setCharAt(10 + idx, c) }
        }

        // 생년월일(숫자)
        for (i in 13..18) {
            when (sb[i]) {
                'O' -> sb.setCharAt(i, '0')
                'I' -> sb.setCharAt(i, '1')
                'S' -> sb.setCharAt(i, '5')
                'B' -> sb.setCharAt(i, '8')
            }
        }

        // 만료일(숫자)
        for (i in 21..26) {
            when (sb[i]) {
                'O' -> sb.setCharAt(i, '0')
                'I' -> sb.setCharAt(i, '1')
                'S' -> sb.setCharAt(i, '5')
                'B' -> sb.setCharAt(i, '8')
            }
        }

        return sb.toString()
    }

    // --- Check digit ---

    private fun charToValue(c: Char): Int {
        return when {
            c in '0'..'9' -> c.code - '0'.code
            c in 'A'..'Z' -> c.code - 'A'.code + 10
            c == '<' -> 0
            else -> throw IllegalArgumentException("Invalid MRZ char: $c")
        }
    }

    private fun computeCheckDigit(data: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in data.indices) {
            sum += charToValue(data[i]) * weights[i % 3]
        }
        return sum % 10
    }
}
