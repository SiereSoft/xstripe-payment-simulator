package com.fakestripe.billing

import com.fakestripe.error.StripeException

/**
 * Stripe's charge limits: the currencies it settles, the minimum amount it will charge in each
 * (https://docs.stripe.com/currencies#minimum-and-maximum-charge-amounts) and the eight-digit
 * maximum. Amounts are in the currency's minor unit (cents; yen for zero-decimal currencies).
 */
object Amounts {
    /** Stripe's eight-digit ceiling, e.g. 99999999 = $999,999.99. */
    const val MAX_AMOUNT = 99_999_999L

    /** Minimum chargeable amounts Stripe publishes; other currencies use [DEFAULT_MINIMUM]. */
    private val MINIMUMS = mapOf(
        "usd" to 50L, "aed" to 200L, "aud" to 50L, "bgn" to 100L, "brl" to 50L, "cad" to 50L, "chf" to 50L,
        "czk" to 1500L, "dkk" to 250L, "eur" to 50L, "gbp" to 30L, "hkd" to 400L, "huf" to 17500L, "inr" to 50L,
        "jpy" to 50L, "mxn" to 1000L, "myr" to 200L, "nok" to 300L, "nzd" to 50L, "pln" to 200L, "ron" to 200L,
        "sek" to 300L, "sgd" to 50L, "thb" to 1000L,
    )
    private const val DEFAULT_MINIMUM = 50L

    /** Presentment currencies Stripe supports for card payments. */
    val SUPPORTED: Set<String> = (
        "usd aed afn all amd ang aoa ars aud awg azn bam bbd bdt bgn bif bmd bnd bob brl bsd bwp byn bzd cad " +
            "cdf chf clp cny cop crc cve czk djf dkk dop dzd egp etb eur fjd fkp gbp gel gip gmd gnf gtq gyd hkd " +
            "hnl htg huf idr ils inr isk jmd jpy kes kgs khr kmf krw kyd kzt lak lbp lkr lrd lsl mad mdl mga mkd " +
            "mmk mnt mop mur mvr mwk mxn myr mzn nad ngn nio nok npr nzd pab pen pgk php pkr pln pyg qar ron rsd " +
            "rub rwf sar sbd scr sek sgd shp sle sos srd std szl thb tjs top try ttd twd tzs uah ugx uyu uzs vnd " +
            "vuv wst xaf xcd xof xpf yer zar zmw"
        ).split(' ').toSet()

    fun minimum(currency: String): Long = MINIMUMS[currency] ?: DEFAULT_MINIMUM

    fun requireSupportedCurrency(currency: String, param: String) {
        if (currency !in SUPPORTED) {
            throw StripeException.invalidRequest(
                "Invalid currency: $currency. Stripe currently supports these currencies: ${SUPPORTED.sorted().joinToString()}",
                param = param,
                code = "currency_not_supported",
            )
        }
    }

    fun requireNotTooLarge(amount: Long, currency: String, param: String) {
        if (amount > MAX_AMOUNT) {
            throw StripeException.invalidRequest(
                "Amount must be no more than ${format(MAX_AMOUNT, currency)}",
                param = param,
                code = "amount_too_large",
            )
        }
    }

    /**
     * An amount Stripe will charge: at most eight digits and at least the currency's minimum
     * ([allowZero]: nothing due at all is fine, as for a free subscription).
     */
    fun requireChargeable(amount: Long, currency: String, allowZero: Boolean, param: String) {
        requireNotTooLarge(amount, currency, param)
        if (amount == 0L && allowZero) return
        if (amount < minimum(currency)) {
            throw StripeException.invalidRequest(
                "The Checkout Session's total amount due must add up to at least ${format(minimum(currency), currency)}",
                param = param,
                code = "amount_too_small",
            )
        }
    }

    private val ZERO_DECIMAL = setOf(
        "bif", "clp", "djf", "gnf", "jpy", "kmf", "krw", "mga", "pyg", "rwf", "ugx", "vnd", "vuv", "xaf", "xof", "xpf",
    )

    private fun format(amount: Long, currency: String): String =
        if (currency in ZERO_DECIMAL) "$amount $currency" else "%d.%02d %s".format(amount / 100, amount % 100, currency)
}
