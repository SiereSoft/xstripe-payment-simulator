package com.fakestripe.cards

/**
 * Stripe's documented test cards and their behaviors. The card NUMBER decides what
 * happens when a PaymentIntent is confirmed, so the state machine is data-driven
 * from this table rather than hard-coded branches.
 *
 * See https://stripe.com/docs/testing.
 */
object TestCards {

    sealed interface Outcome {
        /** Charge succeeds. */
        object Succeed : Outcome

        /** Requires 3-D Secure / additional customer action. */
        object RequiresAction : Outcome

        /**
         * Charge is declined. [code] is the top-level error code (usually
         * "card_declined"); [declineCode] is the finer-grained reason.
         */
        data class Decline(
            val code: String,
            val declineCode: String?,
            val message: String,
        ) : Outcome
    }

    data class Card(
        val brand: String,
        val funding: String = "credit",
        val country: String = "US",
        val outcome: Outcome = Outcome.Succeed,
    )

    // Common decline shapes ---------------------------------------------------
    private fun decline(declineCode: String, message: String) =
        Outcome.Decline("card_declined", declineCode, message)

    private val genericDecline = decline("generic_decline", "Your card was declined.")
    private val insufficientFunds = decline("insufficient_funds", "Your card has insufficient funds.")
    private val lostCard = decline("lost_card", "Your card was declined.")
    private val stolenCard = decline("stolen_card", "Your card was declined.")
    private val expiredCard = Outcome.Decline("expired_card", null, "Your card has expired.")
    private val incorrectCvc = Outcome.Decline("incorrect_cvc", null, "Your card's security code is incorrect.")
    private val processingError =
        Outcome.Decline("processing_error", null, "An error occurred while processing your card. Try again in a little bit.")

    /** number -> behavior. */
    private val byNumber: Map<String, Card> = mapOf(
        // Successful cards
        "4242424242424242" to Card("visa"),
        "4000056655665556" to Card("visa", funding = "debit"),
        "5555555555554444" to Card("mastercard"),
        "5200828282828210" to Card("mastercard", funding = "debit"),
        "378282246310005" to Card("amex"),
        "6011111111111117" to Card("discover"),
        // Declines
        "4000000000000002" to Card("visa", outcome = genericDecline),
        "4000000000009995" to Card("visa", outcome = insufficientFunds),
        "4000000000009987" to Card("visa", outcome = lostCard),
        "4000000000009979" to Card("visa", outcome = stolenCard),
        "4000000000000069" to Card("visa", outcome = expiredCard),
        "4000000000000127" to Card("visa", outcome = incorrectCvc),
        "4000000000000119" to Card("visa", outcome = processingError),
        // 3-D Secure / requires_action
        "4000002500003155" to Card("visa", outcome = Outcome.RequiresAction),
        "4000000000003220" to Card("visa", outcome = Outcome.RequiresAction),
    )

    /**
     * Shared test PaymentMethod tokens. Passing `payment_method: 'pm_card_visa'`
     * straight into confirm (without first creating a PaymentMethod) is a core
     * pattern in Stripe's own SDK test suites, so we resolve these to a number.
     */
    private val tokenToNumber: Map<String, String> = mapOf(
        "pm_card_visa" to "4242424242424242",
        "pm_card_visa_debit" to "4000056655665556",
        "pm_card_mastercard" to "5555555555554444",
        "pm_card_amex" to "378282246310005",
        "pm_card_discover" to "6011111111111117",
        "pm_card_chargeDeclined" to "4000000000000002",
        "pm_card_chargeDeclinedInsufficientFunds" to "4000000000009995",
        "pm_card_chargeDeclinedLostCard" to "4000000000009987",
        "pm_card_chargeDeclinedStolenCard" to "4000000000009979",
        "pm_card_chargeDeclinedExpiredCard" to "4000000000000069",
        "pm_card_chargeDeclinedIncorrectCvc" to "4000000000000127",
        "pm_card_chargeDeclinedProcessingError" to "4000000000000119",
        "pm_card_authenticationRequired" to "4000002500003155",
        "pm_card_threeDSecure2Required" to "4000000000003220",
    )

    fun isToken(id: String): Boolean = tokenToNumber.containsKey(id)

    fun numberForToken(token: String): String? = tokenToNumber[token]

    /** Look up a card by number, inferring brand for unknown numbers. */
    fun forNumber(number: String): Card {
        byNumber[number]?.let { return it }
        return Card(brand = inferBrand(number))
    }

    fun last4(number: String): String = number.takeLast(4)

    private fun inferBrand(number: String): String = when {
        number.startsWith("4") -> "visa"
        number.startsWith("34") || number.startsWith("37") -> "amex"
        number.startsWith("6") -> "discover"
        number.firstOrNull() == '5' || number.startsWith("2") -> "mastercard"
        else -> "unknown"
    }
}
