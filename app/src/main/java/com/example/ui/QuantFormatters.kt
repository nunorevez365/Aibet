package com.example.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object QuantFormatters {
    private val ptLocale = Locale("pt", "PT")

    fun euro(value: Double): String = String.format(ptLocale, "%.2f€", value)

    fun signedEuro(value: Double): String {
        val sign = if (value > 0.0) "+" else ""
        return String.format(ptLocale, "%s%.2f€", sign, value)
    }

    fun odd(value: Double): String = String.format(Locale.US, "%.2f", value)

    fun percent(fraction: Double): String = String.format(ptLocale, "%.1f%%", fraction * 100.0)

    fun percentDirect(percentValue: Double): String = String.format(ptLocale, "%.1f%%", percentValue)

    fun signedPercentDirect(percentValue: Double): String {
        val sign = if (percentValue > 0.0) "+" else ""
        return String.format(ptLocale, "%s%.1f%%", sign, percentValue)
    }

    fun signedPercentFraction(fraction: Double): String = signedPercentDirect(fraction * 100.0)

    fun xg(value: Double): String = String.format(Locale.US, "%.2f", value)

    fun timestampWet(millis: Long): String {
        val fmt = SimpleDateFormat("dd/MM HH:mm", ptLocale).apply {
            timeZone = TimeZone.getTimeZone("Europe/Lisbon")
        }
        return fmt.format(Date(millis))
    }
}
