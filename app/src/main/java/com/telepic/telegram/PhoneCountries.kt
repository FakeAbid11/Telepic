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

    /**
     * The dial-code table, alphabetical by name.
     *
     * It used to hold 64 countries and was missing several very large ones — **India** above all,
     * which meant a user whose SIM was Indian got the United States default and had to type
     * `+91` by hand. The table now covers the countries a phone number realistically comes from.
     */
    val All: List<CountryDialCode> = listOf(
        CountryDialCode("AF", "Afghanistan", "+93", "🇦🇫"),
        CountryDialCode("AL", "Albania", "+355", "🇦🇱"),
        CountryDialCode("DZ", "Algeria", "+213", "🇩🇿"),
        CountryDialCode("AR", "Argentina", "+54", "🇦🇷"),
        CountryDialCode("AM", "Armenia", "+374", "🇦🇲"),
        CountryDialCode("AU", "Australia", "+61", "🇦🇺"),
        CountryDialCode("AT", "Austria", "+43", "🇦🇹"),
        CountryDialCode("AZ", "Azerbaijan", "+994", "🇦🇿"),
        CountryDialCode("BH", "Bahrain", "+973", "🇧🇭"),
        CountryDialCode("BD", "Bangladesh", "+880", "🇧🇩"),
        CountryDialCode("BY", "Belarus", "+375", "🇧🇾"),
        CountryDialCode("BE", "Belgium", "+32", "🇧🇪"),
        CountryDialCode("BO", "Bolivia", "+591", "🇧🇴"),
        CountryDialCode("BA", "Bosnia and Herzegovina", "+387", "🇧🇦"),
        CountryDialCode("BR", "Brazil", "+55", "🇧🇷"),
        CountryDialCode("BG", "Bulgaria", "+359", "🇧🇬"),
        CountryDialCode("KH", "Cambodia", "+855", "🇰🇭"),
        CountryDialCode("CM", "Cameroon", "+237", "🇨🇲"),
        CountryDialCode("CA", "Canada", "+1", "🇨🇦"),
        CountryDialCode("CL", "Chile", "+56", "🇨🇱"),
        CountryDialCode("CN", "China", "+86", "🇨🇳"),
        CountryDialCode("CO", "Colombia", "+57", "🇨🇴"),
        CountryDialCode("CR", "Costa Rica", "+506", "🇨🇷"),
        CountryDialCode("HR", "Croatia", "+385", "🇭🇷"),
        CountryDialCode("CY", "Cyprus", "+357", "🇨🇾"),
        CountryDialCode("CZ", "Czechia", "+420", "🇨🇿"),
        CountryDialCode("DK", "Denmark", "+45", "🇩🇰"),
        CountryDialCode("DO", "Dominican Republic", "+1 809", "🇩🇴"),
        CountryDialCode("EC", "Ecuador", "+593", "🇪🇨"),
        CountryDialCode("EG", "Egypt", "+20", "🇪🇬"),
        CountryDialCode("SV", "El Salvador", "+503", "🇸🇻"),
        CountryDialCode("EE", "Estonia", "+372", "🇪🇪"),
        CountryDialCode("ET", "Ethiopia", "+251", "🇪🇹"),
        CountryDialCode("FJ", "Fiji", "+679", "🇫🇯"),
        CountryDialCode("FI", "Finland", "+358", "🇫🇮"),
        CountryDialCode("FR", "France", "+33", "🇫🇷"),
        CountryDialCode("GE", "Georgia", "+995", "🇬🇪"),
        CountryDialCode("DE", "Germany", "+49", "🇩🇪"),
        CountryDialCode("GH", "Ghana", "+233", "🇬🇭"),
        CountryDialCode("GR", "Greece", "+30", "🇬🇷"),
        CountryDialCode("GT", "Guatemala", "+502", "🇬🇹"),
        CountryDialCode("HN", "Honduras", "+504", "🇭🇳"),
        CountryDialCode("HK", "Hong Kong", "+852", "🇭🇰"),
        CountryDialCode("HU", "Hungary", "+36", "🇭🇺"),
        CountryDialCode("IS", "Iceland", "+354", "🇮🇸"),
        CountryDialCode("IN", "India", "+91", "🇮🇳"),
        CountryDialCode("ID", "Indonesia", "+62", "🇮🇩"),
        CountryDialCode("IR", "Iran", "+98", "🇮🇷"),
        CountryDialCode("IQ", "Iraq", "+964", "🇮🇶"),
        CountryDialCode("IE", "Ireland", "+353", "🇮🇪"),
        CountryDialCode("IL", "Israel", "+972", "🇮🇱"),
        CountryDialCode("IT", "Italy", "+39", "🇮🇹"),
        CountryDialCode("CI", "Ivory Coast", "+225", "🇨🇮"),
        CountryDialCode("JM", "Jamaica", "+1 876", "🇯🇲"),
        CountryDialCode("JP", "Japan", "+81", "🇯🇵"),
        CountryDialCode("JO", "Jordan", "+962", "🇯🇴"),
        CountryDialCode("KZ", "Kazakhstan", "+7", "🇰🇿"),
        CountryDialCode("KE", "Kenya", "+254", "🇰🇪"),
        CountryDialCode("KR", "South Korea", "+82", "🇰🇷"),
        CountryDialCode("KW", "Kuwait", "+965", "🇰🇼"),
        CountryDialCode("LA", "Laos", "+856", "🇱🇦"),
        CountryDialCode("LV", "Latvia", "+371", "🇱🇻"),
        CountryDialCode("LB", "Lebanon", "+961", "🇱🇧"),
        CountryDialCode("LT", "Lithuania", "+370", "🇱🇹"),
        CountryDialCode("LU", "Luxembourg", "+352", "🇱🇺"),
        CountryDialCode("MO", "Macau", "+853", "🇲🇴"),
        CountryDialCode("MY", "Malaysia", "+60", "🇲🇾"),
        CountryDialCode("MV", "Maldives", "+960", "🇲🇻"),
        CountryDialCode("MT", "Malta", "+356", "🇲🇹"),
        CountryDialCode("MX", "Mexico", "+52", "🇲🇽"),
        CountryDialCode("MD", "Moldova", "+373", "🇲🇩"),
        CountryDialCode("MN", "Mongolia", "+976", "🇲🇳"),
        CountryDialCode("MA", "Morocco", "+212", "🇲🇦"),
        CountryDialCode("MZ", "Mozambique", "+258", "🇲🇿"),
        CountryDialCode("MM", "Myanmar", "+95", "🇲🇲"),
        CountryDialCode("NP", "Nepal", "+977", "🇳🇵"),
        CountryDialCode("NL", "Netherlands", "+31", "🇳🇱"),
        CountryDialCode("NZ", "New Zealand", "+64", "🇳🇿"),
        CountryDialCode("NG", "Nigeria", "+234", "🇳🇬"),
        CountryDialCode("MK", "North Macedonia", "+389", "🇲🇰"),
        CountryDialCode("NO", "Norway", "+47", "🇳🇴"),
        CountryDialCode("OM", "Oman", "+968", "🇴🇲"),
        CountryDialCode("PK", "Pakistan", "+92", "🇵🇰"),
        CountryDialCode("PS", "Palestine", "+970", "🇵🇸"),
        CountryDialCode("PA", "Panama", "+507", "🇵🇦"),
        CountryDialCode("PG", "Papua New Guinea", "+675", "🇵🇬"),
        CountryDialCode("PY", "Paraguay", "+595", "🇵🇾"),
        CountryDialCode("PE", "Peru", "+51", "🇵🇪"),
        CountryDialCode("PH", "Philippines", "+63", "🇵🇭"),
        CountryDialCode("PL", "Poland", "+48", "🇵🇱"),
        CountryDialCode("PT", "Portugal", "+351", "🇵🇹"),
        CountryDialCode("PR", "Puerto Rico", "+1 787", "🇵🇷"),
        CountryDialCode("QA", "Qatar", "+974", "🇶🇦"),
        CountryDialCode("RO", "Romania", "+40", "🇷🇴"),
        CountryDialCode("RU", "Russia", "+7", "🇷🇺"),
        CountryDialCode("SA", "Saudi Arabia", "+966", "🇸🇦"),
        CountryDialCode("SN", "Senegal", "+221", "🇸🇳"),
        CountryDialCode("RS", "Serbia", "+381", "🇷🇸"),
        CountryDialCode("SG", "Singapore", "+65", "🇸🇬"),
        CountryDialCode("SK", "Slovakia", "+421", "🇸🇰"),
        CountryDialCode("SI", "Slovenia", "+386", "🇸🇮"),
        CountryDialCode("ZA", "South Africa", "+27", "🇿🇦"),
        CountryDialCode("ES", "Spain", "+34", "🇪🇸"),
        CountryDialCode("LK", "Sri Lanka", "+94", "🇱🇰"),
        CountryDialCode("SE", "Sweden", "+46", "🇸🇪"),
        CountryDialCode("CH", "Switzerland", "+41", "🇨🇭"),
        CountryDialCode("TW", "Taiwan", "+886", "🇹🇼"),
        CountryDialCode("TZ", "Tanzania", "+255", "🇹🇿"),
        CountryDialCode("TH", "Thailand", "+66", "🇹🇭"),
        CountryDialCode("TT", "Trinidad and Tobago", "+1 868", "🇹🇳"),
        CountryDialCode("TN", "Tunisia", "+216", "🇹🇳"),
        CountryDialCode("TR", "Turkey", "+90", "🇹🇷"),
        CountryDialCode("UG", "Uganda", "+256", "🇺🇬"),
        CountryDialCode("UA", "Ukraine", "+380", "🇺🇦"),
        CountryDialCode("AE", "United Arab Emirates", "+971", "🇦🇪"),
        CountryDialCode("GB", "United Kingdom", "+44", "🇬🇧"),
        CountryDialCode("US", "United States", "+1", "🇺🇸"),
        CountryDialCode("UZ", "Uzbekistan", "+998", "🇺🇿"),
        CountryDialCode("VE", "Venezuela", "+58", "🇻🇪"),
        CountryDialCode("VN", "Vietnam", "+84", "🇻🇳"),
        CountryDialCode("YE", "Yemen", "+967", "🇾🇪"),
        CountryDialCode("ZM", "Zambia", "+260", "🇿🇲"),
        CountryDialCode("ZW", "Zimbabwe", "+263", "🇿🇼"),
    )

    private val byIso = All.associateBy { it.iso }

    fun byIso(iso: String?): CountryDialCode? = iso?.let { byIso[it.uppercase()] }
}

/**
 * E.164-ify [input] for TDLib: an explicit `+`-prefixed international number keeps its country
 * code (only separators are stripped); a `00` international prefix is honoured the same way; anything
 * else is combined with [dialCode] after dropping non-digits and a leading trunk zero (the `0` in
 * `0300 123 4567` is a national prefix, not part of the subscriber number).
 */
fun normalizeE164(input: String, dialCode: String): String {
    val trimmed = input.trim()
    if (trimmed.startsWith("+")) return "+" + trimmed.drop(1).filter { it.isDigit() }
    val digits = trimmed.filter { it.isDigit() }
    // "00" is the international prefix in most of the world, so "00 44 20 ..." is a +44 number
    // whatever the dropdown says. Treating it as a national number dropped the leading zeros and
    // produced "+1442012345678" — a United States number built from a British one.
    if (digits.startsWith("00")) {
        val international = digits.drop(2)
        return if (international.isEmpty()) trimmed else "+$international"
    }
    val national = digits.trimStart('0')
    return if (national.isEmpty()) trimmed else dialCode + national
}
