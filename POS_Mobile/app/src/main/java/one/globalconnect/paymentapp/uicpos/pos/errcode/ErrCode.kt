package one.globalconnect.paymentapp.uicpos.pos.errcode

import android.content.Context
import one.globalconnect.paymentapp.R


enum class ErrCode(msgId: Int, val timeoutSec: Int) {

    NO_ERR                   (R.string.err_user_cancel, 0),
    ERR_TERMINAL_API_STATUS       (R.string.err_terminal_api_status, Constants.USER_OPER_TIMEOUT),
    ERR_USERCANCEL           (R.string.err_user_cancel, Constants.USER_OPER_TIMEOUT_5),
    ERR_TIMEOUT              (R.string.err_timeout, Constants.USER_OPER_TIMEOUT_5),
    ERR_NOT_SUPPORT_TRANS    (R.string.err_not_support_trans, Constants.USER_OPER_TIMEOUT),
    ERR_NORECORD             (R.string.err_unknow, Constants.USER_OPER_TIMEOUT),
    ERR_NO_DISP              (R.string.err_no_disp, Constants.USER_OPER_TIMEOUT),
    ERR_INVALID_PARAM        (R.string.err_invalid_param, Constants.USER_OPER_TIMEOUT),
    ERR_INVALID_AMOUNT       (R.string.err_amount_invalid, Constants.USER_OPER_TIMEOUT),
    ERR_TIPS_AMOUNT_INCORRECT(R.string.err_tips_amout_incorrect, Constants.USER_OPER_TIMEOUT),
    ERR_PROC_RECV_DATA       (R.string.err_proc_recv_data, Constants.USER_OPER_TIMEOUT),

    /* communication error code */
    ERR_COMM_OPEN_FAIL       (R.string.err_comm_open_failed, Constants.USER_OPER_TIMEOUT_5),
    ERR_COMM_CONNECT_FAIL    (R.string.err_comm_connect_failed, Constants.USER_OPER_TIMEOUT_5),
    ERR_COMM_DISCONNECT_FAIL (R.string.err_comm_disconnect_failed, Constants.USER_OPER_TIMEOUT_5),
    ERR_COMM_SEND_FAIL       (R.string.err_comm_send_failed, Constants.USER_OPER_TIMEOUT_5),
    ERR_COMM_RECEIVE_FAIL    (R.string.err_comm_receive_failed, Constants.USER_OPER_TIMEOUT_5),
    ERR_COMM_RECEIVE_TIMEOUT (R.string.err_comm_receive_timeout, Constants.USER_OPER_TIMEOUT_5),

    ERR_CODE_MOCK_TESTING (R.string.err_mock_code, Constants.USER_PRESS_OK_BUTTON),

    ERR_SSL_INIT_FAILED       (R.string.err_unknow, Constants.USER_OPER_TIMEOUT_5),
    ERR_SSL_CONNECT_FAILED    (R.string.err_unknow, Constants.USER_OPER_TIMEOUT_5),
    ERR_SSL_HANDSHAKE_FAILED  (R.string.err_unknow, Constants.USER_OPER_TIMEOUT_5),
    ERR_SSL_SEND_FAILED       (R.string.err_unknow, Constants.USER_OPER_TIMEOUT_5),
    ERR_SSL_RECEIVE_FAILED    (R.string.err_unknow, Constants.USER_OPER_TIMEOUT_5);

    private val labelID: Int = msgId

    fun getMsg(context: Context) =
        context.getString(labelID)

    fun getId() = this.labelID
}
