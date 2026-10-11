package com.telepic.telegram

/**
 * A country entry for the Telegram phone login: display identity plus its E.164 dial code.
 */
data class CountryDialCode(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flag: String,
)

/**
 * Curated dial-code table for the login screen. Pure Kotlin (no Android imports) so the
 * normalization rules and the table itself are unit-testable on the JVM.
 */
object PhoneCountries {

    /** Used when neither the SIM nor the system locale identifies a listed country. */
    val Default = CountryDialCode("US", "United States", "+1", "🇺🇸")

    /** Common countries, alphabetical by name — the dropdown is short enough to scan. */
    val All: List<CountryDialCode> = listOf(
        CountryDialCode("AF", "Afghanistan", "+93", "🇦🇫"),
        CountryDialCode("AR", "Argentina", "+54", "🇦🇷"),
        CountryDialCode("AU", "Australia", "+61", "🇦🇺"),
        CountryDialCode("AT", "Austria", "+43", "🇦🇹"),
        CountryDialCode("BD", "Bangladesh", "+880", "🇧🇩"),
        CountryDialCode("BE", "Belgium", "+32", "🇧🇪"),
        CountryDialCode("BR", "Brazil", "+55", "🇧🇷"),
        CountryDialCode("CA", "Canada", "+1", "🇨🇦"),
        CountryDialCode("CN", "China", "+86", "🇨🇳"),
        CountryDialCode("CO", "Colombia", "+57", "🇨🇴"),
        CountryDialCode("CZ", "Czechia", "+420", "🇨🇿"),
        CountryDialCode("DK", "Denmark", "+45", "🇩🇰"),
        CountryDialCode("EG", "Egypt", "+20", "🇪🇬"),
        CountryDialCode("FI", "Finland", "+358", "🇫🇮"),
        CountryDialCode("FR", "France", "+33", "🇫🇷"),
        CountryDialCode("DE", "Germany", "+49", "🇩🇪"),
        CountryDialCode("GR", "Greece", "+30", "🇬🇷"),
        CountryDialCode("HK", "Hong Kong", "+852", "🇭🇰"),
        CountryDialCode("HU", "Hungary", "+36", "🇭🇺"),
        CountryDialCode("ID", "Indonesia", "+62", "🇮🇩"),
        CountryDialCode("IR", "Iran", "+98", "🇮🇷"),
        CountryDialCode("IQ", "Iraq", "+964", "🇮🇶"),
        CountryDialCode("IE", "Ireland", "+353", "🇮🇪"),
        CountryDialCode("IL", "Israel", "+972", "🇮🇱"),
        CountryDialCode("IT", "Italy", "+39", "🇮🇹"),
        CountryDialCode("JP", "Japan", "+81", "🇯🇵"),
        CountryDialCode("JO", "Jordan", "+962", "🇯🇴"),
        CountryDialCode("KE", "Kenya", "+254", "🇰🇪"),
        CountryDialCode("KR", "South Korea", "+82", "🇰🇷"),
        CountryDialCode("KW", "Kuwait", "+965", "🇰🇼"),
        CountryDialCode("MY", "Malaysia", "+60", "🇲🇾"),
        CountryDialCode("MX", "Mexico", "+52", "🇲🇽"),
        CountryDialCode("MA", "Morocco", "+212", "🇲🇦"),
        CountryDialCode("NP", "Nepal", "+977", "🇳🇵"),
        CountryDialCode("NL", "Netherlands", "+31", "🇳🇱"),
        CountryDialCode("NZ", "New Zealand", "+64", "🇳🇿"),
        CountryDialCode("NG", "Nigeria", "+234", "🇳🇬"),
        CountryDialCode("NO", "Norway", "+47", "🇳🇴"),
        CountryDialCode("OM", "Oman", "+968", "🇴🇲"),
        CountryDialCode("PK", "Pakistan", "+92", "🇵🇰"),
        CountryDialCode("PS", "Palestine", "+970", "🇵🇸"),
        CountryDialCode("PE", "Peru", "+51", "🇵🇪"),
        CountryDialCode("PH", "Philippines", "+63", "🇵🇭"),
        CountryDialCode("PL", "Poland", "+48", "🇵🇱"),
        CountryDialCode("PT", "Portugal", "+351", "🇵🇹"),
        CountryDialCode("QA", "Qatar", "+974", "🇶🇦"),
        CountryDialCode("RO", "Romania", "+40", "🇷🇴"),
        CountryDialCode("RU", "Russia", "+7", "🇷🇺"),
        CountryDialCode("SA", "Saudi Arabia", "+966", "🇸🇦"),
        CountryDialCode("SG", "Singapore", "+65", "🇸🇬"),
        CountryDialCode("ZA", "South Africa", "+27", "🇿🇦"),
        CountryDialCode("ES", "Spain", "+34", "🇪🇸"),
        CountryDialCode("LK", "Sri Lanka", "+94", "🇱🇰"),
        CountryDialCode("SE", "Sweden", "+46", "🇸🇪"),
        CountryDialCode("CH", "Switzerland", "+41", "🇨🇭"),
        CountryDialCode("TH", "Thailand", "+66", "🇹🇭"),
        CountryDialCode("TR", "Turkey", "+90", "🇹🇷"),
        CountryDialCode("UA", "Ukraine", "+380", "🇺🇦"),
        CountryDialCode("AE", "United Arab Emirates", "+971", "🇦🇪"),
        CountryDialCode("GB", "United Kingdom", "+44", "🇬🇧"),
        CountryDialCode("US", "United States", "+1", "🇺🇸"),
        CountryDialCode("VN", "Vietnam", "+84", "🇻🇳"),
        CountryDialCode("YE", "Yemen", "+967", "🇾🇪"),
    )

    private val byIso = All.associateBy { it.iso }

    fun byIso(iso: String?): CountryDialCode? = iso?.let { byIso[it.uppercase()] }
}

/**
 * E.164-ify [input] for TDLib: an explicit `+`-prefixed international number keeps its country
 * code (only separators are stripped); anything else is combined with [dialCode] after dropping
 * non-digits and a leading trunk zero (the `0` in `0300 123 4567` is a national prefix, not part
 * of the subscriber number).
 */
fun normalizeE164(input: String, dialCode: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("+")) return "+" + trimmed.drop(1).filter { it.isDigit() }
    val national = trimmed.filter { it.isDigit() }.trimStart('0')
    return if (national.isEmpty()) trimmed else dialCode + national
}
