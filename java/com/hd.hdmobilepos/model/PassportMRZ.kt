package com.hd.hdmobilepos.model

import com.hd.hdmobilepos.util.MRZUtils

/**
 * 여권 MRZ(TD3, 2줄 44자) 파서.
 *
 * NOTE: 검증(체크디지트)은 MRZUtils.lookslikePassportMRZ()에서 수행하고,
 * 이 클래스는 "필드 파싱"을 담당합니다.
 */
class PassportMRZ(mrz: String) {

    val mrz: String = mrz

    val line1: String
    val line2: String

    // 필드
    var documentType: String = "" // P
        private set

    var issuingCountry: String = "" // KOR/CHN...
        private set

    var lastName: String = ""
        private set

    var firstName: String = ""
        private set

    var passportNumber: String = ""
        private set

    var nationality: String = ""
        private set

    var birthDate: String = "" // YYMMDD
        private set

    var sex: String = "" // M/F/X/<
        private set

    var expiryDate: String = "" // YYMMDD
        private set

    init {
        val lines = mrz.split("\n")
        if (lines.size >= 2) {
            line1 = MRZUtils.normalizeMrzChars(lines[0], true)
            line2 = MRZUtils.normalizeMrzChars(lines[1], false)
            parseMRZ()
        } else {
            line1 = ""
            line2 = ""
        }
    }

    private fun parseMRZ() {
        try {
            // --- Line 1 ---
            documentType = normalizeAlpha(line1.substring(0, 1))
            issuingCountry = normalizeAlpha(line1.substring(2, 5))

            val nameField = line1.substring(5)
            val nameParts = nameField.split("<<")
            lastName = if (nameParts.isNotEmpty()) {
                normalizeAlpha(nameParts[0].replace("<", " ").trim())
            } else {
                ""
            }
            firstName = if (nameParts.size > 1) {
                normalizeAlpha(nameParts[1].replace("<", " ").trim())
            } else {
                ""
            }

            // --- Line 2 ---
            passportNumber = line2.substring(0, 9).replace("<", "")
            nationality = normalizeAlpha(line2.substring(10, 13))
            birthDate = normalizeNumeric(line2.substring(13, 19))
            sex = line2.substring(20, 21)
            expiryDate = normalizeNumeric(line2.substring(21, 27))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 문자만 허용 (숫자 0 → 영문 O로 보정)
     */
    private fun normalizeAlpha(s: String): String {
        return s
            .replace('0', 'O')
            .replace('1', 'I')
            .replace('5', 'S')
            .replace('8', 'B')
            .replace(Regex("[^A-Z ]"), "")
    }

    /**
     * 숫자만 허용 (영문 I → 숫자 1, 영문 O → 숫자 0 보정)
     */
    private fun normalizeNumeric(s: String): String {
        return s
            .replace('I', '1')
            .replace('O', '0')
            .replace('S', '5')
            .replace('B', '8')
            .replace(Regex("[^0-9]"), "")
    }

    override fun toString(): String {
        return "PassportMRZ{" +
            "documentType='$documentType', " +
            "issuingCountry='$issuingCountry', " +
            "lastName='$lastName', " +
            "firstName='$firstName', " +
            "passportNumber='$passportNumber', " +
            "nationality='$nationality', " +
            "birthDate='$birthDate', " +
            "sex='$sex', " +
            "expiryDate='$expiryDate'" +
            "}"
    }
}
