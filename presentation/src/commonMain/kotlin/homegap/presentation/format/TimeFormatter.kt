package homegap.presentation.format

/** Locale-aware date/time rendering, provided by the platform (the browser's `Date`). */
interface TimeFormatter {
    fun time(epochMillis: Long): String
    fun dateTime(epochMillis: Long): String

    /** The day alone: "14 Oct 2026". */
    fun date(epochMillis: Long): String
}
