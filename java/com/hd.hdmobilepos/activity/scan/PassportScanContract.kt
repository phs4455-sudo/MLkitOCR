package com.hd.hdmobilepos.activity.scan

import android.content.Context
import android.content.Intent
import com.hd.hdmobilepos.model.PassportMRZ

object PassportScanContract {
    const val REQUEST_CODE = 1001

    const val EXTRA_BARCODE_VALUE = "barcodeValue"
    const val EXTRA_DOCUMENT_TYPE = "documentType"
    const val EXTRA_ISSUING_COUNTRY = "issuingCountry"
    const val EXTRA_LAST_NAME = "lastName"
    const val EXTRA_FIRST_NAME = "firstName"
    const val EXTRA_PASSPORT_NUMBER = "passportNumber"
    const val EXTRA_NATIONALITY = "nationality"
    const val EXTRA_DATE_OF_BIRTH = "dateOfBirth"
    const val EXTRA_SEX = "sex"
    const val EXTRA_EXPIRATION_DATE = "expirationDate"
    const val EXTRA_RAW_MRZ_LINE_1 = "rawMrzLine1"
    const val EXTRA_RAW_MRZ_LINE_2 = "rawMrzLine2"
    const val EXTRA_MRZ_IS_STRICT = "mrzIsStrict"

    @JvmStatic
    fun createIntent(context: Context): Intent = Intent(context, HDPassportScanActivity::class.java)

    @JvmStatic
    fun createBarcodeResultIntent(value: String): Intent = Intent().apply {
        putExtra(EXTRA_BARCODE_VALUE, value)
    }

    @JvmStatic
    fun createPassportResultIntent(passport: PassportMRZ, isStrict: Boolean): Intent = Intent().apply {
        putExtra(EXTRA_DOCUMENT_TYPE, passport.documentType)
        putExtra(EXTRA_ISSUING_COUNTRY, passport.issuingCountry)
        putExtra(EXTRA_LAST_NAME, passport.lastName)
        putExtra(EXTRA_FIRST_NAME, passport.firstName)
        putExtra(EXTRA_PASSPORT_NUMBER, passport.passportNumber)
        putExtra(EXTRA_NATIONALITY, passport.nationality)
        putExtra(EXTRA_DATE_OF_BIRTH, passport.birthDate)
        putExtra(EXTRA_SEX, passport.sex)
        putExtra(EXTRA_EXPIRATION_DATE, passport.expiryDate)
        putExtra(EXTRA_RAW_MRZ_LINE_1, passport.line1)
        putExtra(EXTRA_RAW_MRZ_LINE_2, passport.line2)
        putExtra(EXTRA_MRZ_IS_STRICT, isStrict)
    }

    @JvmStatic
    fun parseResult(data: Intent?): ScanResult? {
        if (data == null) return null

        val barcodeValue = data.getStringExtra(EXTRA_BARCODE_VALUE)
        if (!barcodeValue.isNullOrBlank()) {
            return ScanResult.Barcode(barcodeValue)
        }

        val passportNumber = data.getStringExtra(EXTRA_PASSPORT_NUMBER)
        val nationality = data.getStringExtra(EXTRA_NATIONALITY)
        val birthDate = data.getStringExtra(EXTRA_DATE_OF_BIRTH)
        val expiryDate = data.getStringExtra(EXTRA_EXPIRATION_DATE)
        val sex = data.getStringExtra(EXTRA_SEX)
        val lastName = data.getStringExtra(EXTRA_LAST_NAME)
        val firstName = data.getStringExtra(EXTRA_FIRST_NAME)
        val rawLine1 = data.getStringExtra(EXTRA_RAW_MRZ_LINE_1)
        val rawLine2 = data.getStringExtra(EXTRA_RAW_MRZ_LINE_2)
        val isStrict = data.getBooleanExtra(EXTRA_MRZ_IS_STRICT, false)

        if (passportNumber.isNullOrBlank() && nationality.isNullOrBlank() && birthDate.isNullOrBlank() && expiryDate.isNullOrBlank()) {
            return null
        }

        return ScanResult.Passport(
            documentType = data.getStringExtra(EXTRA_DOCUMENT_TYPE).orEmpty(),
            issuingCountry = data.getStringExtra(EXTRA_ISSUING_COUNTRY).orEmpty(),
            lastName = lastName.orEmpty(),
            firstName = firstName.orEmpty(),
            passportNumber = passportNumber.orEmpty(),
            nationality = nationality.orEmpty(),
            dateOfBirth = birthDate.orEmpty(),
            sex = sex.orEmpty(),
            expirationDate = expiryDate.orEmpty(),
            rawMrzLine1 = rawLine1.orEmpty(),
            rawMrzLine2 = rawLine2.orEmpty(),
            isStrict = isStrict
        )
    }

    sealed class ScanResult {
        data class Barcode(val value: String) : ScanResult()

        data class Passport(
            val documentType: String,
            val issuingCountry: String,
            val lastName: String,
            val firstName: String,
            val passportNumber: String,
            val nationality: String,
            val dateOfBirth: String,
            val sex: String,
            val expirationDate: String,
            val rawMrzLine1: String,
            val rawMrzLine2: String,
            val isStrict: Boolean
        ) : ScanResult()
    }
}
