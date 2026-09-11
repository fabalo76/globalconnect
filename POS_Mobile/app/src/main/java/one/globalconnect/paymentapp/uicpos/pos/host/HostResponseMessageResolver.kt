package one.globalconnect.paymentapp.uicpos.pos.host

import one.globalconnect.paymentapp.R
import one.globalconnect.paymentapp.GlobalConnectPaymentApplication
import java.io.IOException
import java.util.Locale

object HostResponseMessageResolver {

    private val responseMessages = mapOf(
        "00" to R.string.host_result_transaction_approved,
        "01" to R.string.host_result_please_call,
        "02" to R.string.host_result_please_call,
        "03" to R.string.host_result_invalid_merchant,
        "04" to R.string.host_result_pick_up_card,
        "05" to R.string.host_result_declined_by_issuer,
        "07" to R.string.host_result_declined_07,
        "10" to R.string.host_result_partial_verify,
        "12" to R.string.host_result_not_allowed_card,
        "13" to R.string.host_result_error_invalid_amount,
        "14" to R.string.host_result_invalid_card,
        "15" to R.string.host_result_no_issuer,
        "16" to R.string.host_result_declined_16,
        "19" to R.string.host_result_reenter_transaction,
        "21" to R.string.host_result_nothing_to_reverse,
        "22" to R.string.host_result_original_not_found,
        "25" to R.string.host_result_original_not_found,
        "30" to R.string.host_result_format_error_30,
        "38" to R.string.host_result_pin_exceeded,
        "40" to R.string.host_result_not_supported,
        "41" to R.string.host_result_lost_card,
        "43" to R.string.host_result_stolen_card,
        "45" to R.string.host_result_installments_not_allowed,
        "46" to R.string.host_result_card_not_active,
        "47" to R.string.host_result_pin_required,
        "48" to R.string.host_result_invalid_quota,
        "51" to R.string.host_result_insufficient_funds,
        "52" to R.string.host_result_no_checking_account,
        "53" to R.string.host_result_no_savings_account,
        "54" to R.string.host_result_expired_card,
        "55" to R.string.host_result_wrong_pin,
        "56" to R.string.host_result_no_card_record,
        "57" to R.string.host_result_not_allowed_terminal,
        "58" to R.string.host_result_not_allowed_card,
        "59" to R.string.host_result_reenter_cvv2,
        "61" to R.string.host_result_limit_exceeded,
        "62" to R.string.host_result_incorrect_mac,
        "63" to R.string.host_result_security_violation,
        "65" to R.string.host_result_use_chip,
        "123" to R.string.host_result_use_chip,
        "75" to R.string.host_result_pin_exceeded,
        "77" to R.string.host_result_settlement_error,
        "78" to R.string.host_result_original_not_found,
        "79" to R.string.host_result_incorrect_cvv2,
        "81" to R.string.host_result_customer_info_error,
        "82" to R.string.host_result_prepaid_code_missing,
        "83" to R.string.host_result_customer_confirmation_required,
        "85" to R.string.host_result_batch_not_found,
        "89" to R.string.host_result_terminal_id_invalid,
        "91" to R.string.host_result_timeout_91,
        "92" to R.string.host_result_routing_error,
        "94" to R.string.host_result_duplicated_sequence,
        "95" to R.string.host_result_batch_transfer,
        "96" to R.string.host_result_system_error_96,
        "PD" to R.string.host_result_partial_declined_user,
        "Y1" to R.string.host_result_offline_first_approved,
        "Z1" to R.string.host_result_offline_first_declined,
        "Y3" to R.string.host_result_offline_second_approved,
        "Z3" to R.string.host_result_offline_second_declined,
        "CE" to R.string.host_result_communication_failure,
        "NR" to R.string.host_result_no_server_response,
        "SN" to R.string.host_result_wrong_serial,
    )

    fun resolve(code: String?): String? {
        val sanitized = code?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US) ?: return null
        val resId = responseMessages[sanitized] ?: return null
        return GlobalConnectPaymentApplication.instance.getString(resId)
    }

    fun resolveOrFallback(code: String?): String {
        val sanitized = code?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(Locale.US)
        val resolved = resolve(sanitized)
        if (resolved != null) return resolved
        val context = GlobalConnectPaymentApplication.instance
        return sanitized?.let { context.getString(R.string.host_response_unknown, it) }
            ?: context.getString(R.string.host_response_missing_code)
    }
}

fun formatHostErrorMessage(error: Throwable): String {
    val context = GlobalConnectPaymentApplication.instance
    return when {
        error is IOException || error.cause is IOException ->
            context.getString(R.string.host_result_communication_failure)
        else -> {
            val reason = error.localizedMessage?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
            context.getString(R.string.host_result_error_generic, reason)
        }
    }
}
