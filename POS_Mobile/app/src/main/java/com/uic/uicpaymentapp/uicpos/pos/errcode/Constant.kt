package com.uic.uicpaymentapp.uicpos.pos.errcode


object Constants {

    // Terminal Command ID
    const val CMD_ID_START_TXN    = "TxnStart"
    const val CMD_ID_PRE_TXN      = "TxnPreprocess"
    const val CMD_ID_GET_RESULT   = "TxnGetResult"
    const val CMD_ID_CANCEL_TXN   = "TxnCancel"
    const val CMD_ID_SETTLE       = "TxnSettlement"
    const val CMD_ID_REPORT_MGMT  = "ReportMgmt"
    const val CMD_ID_DIAG_MGMT    = "DiagMgmt"
    const val CMD_ID_SYSTEM_MGMT  = "SystemMgmt"

    // Terminal Transaction Type
    const val HOST_TRANS_SALE         = "Sale"
    const val HOST_TRANS_AUTH_ONLY    = "AuthOnly"
    const val HOST_TRANS_AUTH_ADD     = "AdditionalAuth"
    const val HOST_TRANS_AUTH_CAPTURE = "AuthCapture"
    const val HOST_TRANS_TIP_ADJ      = "TipAdj"
    const val HOST_TRANS_VOID         = "Void"
    const val HOST_TRANS_REFUND       = "Refund"
    const val HOST_TRANS_REVERSAL     = "Reversal"
    const val HOST_TRANS_FORCE_SALE   = "ForceSale"
    const val HOST_TRANS_TOKEN_REQ    = "TokenRequest"
    const val HOST_TRANS_TOKEN_SALE   = "Sale"
    const val HOST_TRANS_MANUAL_SALE  = "Sale"
    const val HOST_TRANS_MANUAL_REFUND     = "Refund"
    const val HOST_TRANS_MANUAL_AUTH       = "AuthOnly"
    const val HOST_TRANS_TOKEN_AUTH        = "AuthOnly"
    const val HOST_TRANS_MANUAL_FORCE_SALE = "ForceSale"
    const val HOST_TRANS_MOTO              = "Sale"
    const val HOST_TRANS_SETTLEMENT        = "Settlement"

    const val HOST_TRANS_CASH           = "Cash"
    const val HOST_TRANS_PAYMENT        = "Payment"
    const val HOST_TRANS_LOYALTYSALE    = "LoyaltySale"
    const val HOST_TRANS_LOYALTYBALANCE = "LoyaltyBalance"
    const val HOST_TRANS_QUOTASALE      = "QuotaSale"
    const val HOST_TRANS_EXTRASSALE     = "ExtrasSale"
    const val HOST_TRANS_EXTRASBALANCE  = "ExtrasBalance"
    const val HOST_TRANS_CHECKIN        = "CheckIN"
    const val HOST_TRANS_CHECKOUT       = "CheckOOUT"
    const val HOST_TRANS_VOIDCHECKIN    = "VoidCheckIN"


    // Terminal report IDs
    const val REPORT_VIEW_PEDDING_TXN  = "ViewPendingRecord"
    const val REPORT_DEL_PEDDING_TXN   = "DelPendingRecord"
    const val REPORT_VIEW_FAILED_TXN   = "ViewFailedRecord"
    const val REPORT_DEL_FAILED_TXN    = "DelFailedRecord"
    const val REPORT_VIEW_SYSTEM_LOG   = "LogExpor"

    // Terminal information Management ID
    const val INFO_GET_SYS_VER                     = "GetSystemVer"
    const val INFO_GET_PERIPHERAL_VER              = "GetPeripheralVer"
    const val INFO_GET_SYS_TIME                    = "GetSystemTime"
    const val INFO_SET_SYS_TIME                    = "SetSystemTime"
    const val INFO_GET_PERIPHERAL_TIME             = "GetPeripheralTime"
    const val INFO_SET_PERIPHERAL_TIME             = "SetPeripheralTime"
    const val INFO_RETRIEVE_PAYMENT_PROCESSOR_INFO = "RetrievePaymentProcessorInfo"
    const val INFO_GET_DEVICE_CONFIE               = "GetDeviceConfig"

    // Terminal system management ID
    const val SYSTEM_REBOOT           = "RebootSystem"
    const val SYSTEM_UPDATE_PROGRAM   = "UpdateSysProgram"
    const val SYSTEM_AUDIBLE_ALARM    = "AudibleAlarm"
    const val SYSTEM_HOUSE_KEEPING    = "Housekeeping"
    const val SYSTEM_DUMP_SYSTEM_LOG  = "DumpSystemLog"
    const val SYSTEM_DEL_SYSTEM_LOG   = "DeleteSystemLog"

    //--------------------------------------------------------------------------------
    // main page
    const val HOME_TRANS   = "HOME.TRANS"
    const val HOME_QUERY   = "HOME.QUERY"
    const val HOME_SETTING = "HOME.SETTING"

    // transaction category page
    const val HOME_TRANS_RETAIL      = "$HOME_TRANS.RETAIL"
    const val HOME_TRANS_RESTAURANT  = "$HOME_TRANS.RESTAURANT"

    // start transaction page
    const val TRANS_SALE             = "$HOME_TRANS.SALE"
    const val TRANS_REFUND           = "$HOME_TRANS.REFUND"
    const val TRANS_VOID             = "$HOME_TRANS.VOID"
    const val TRANS_PARTIAL_VOID     = "$HOME_TRANS.PARTIAL_VOID"
    const val TRANS_TIPS_ADJS        = "$HOME_TRANS.TIP_ADJUST"
    const val TRANS_TOKEN_SALE       = "$HOME_TRANS.TOKEN_SALE"
    const val TRANS_MANUAL_SALE      = "$HOME_TRANS.MANUAL_SALE"
    const val TRANS_MANUAL_REFUND    = "$HOME_TRANS.MANUAL_REFUND"
    const val TRANS_AUTH             = "$HOME_TRANS.AUTH"
    const val TRANS_ADD_AUTH         = "$HOME_TRANS.ADD_AUTH"
    const val TRANS_AUTH_CAPTURE     = "$HOME_TRANS.AUTH_CAPTURE"
    const val TRANS_FORCE_SALE       = "$HOME_TRANS.FORCE_SALE"
    const val TRANS_REVERSAL         = "$HOME_TRANS.REVERSAL"
    const val TRANS_TOKEN_AUTH       = "$HOME_TRANS.TOKEN_AUTH"
    const val TRANS_MANUAL_AUTH      = "$HOME_TRANS.MANUAL_AUTH"
    const val TRANS_MANUAL_FORCE_SALE= "$HOME_TRANS.MANUAL_FORCE_SALE"
    const val TRANS_MOTO             = "$HOME_TRANS.MOTO"

    const val TRANS_GET_TXN_RESULT   = "$HOME_QUERY.GET_TXN_RESULT"
    const val TRANS_REPORT           = "$HOME_QUERY.REPORT"
    const val TRANS_SETTLE           = "$HOME_QUERY.SETTLE"
    const val TRANS_CANCEL_TXN       = "$HOME_QUERY.CANCEL_TXN"

    // information page
    const val TRANS_VIEW_PEDDING_TXN = "$TRANS_REPORT.VIEW_PEDDING_TXN"
    const val TRANS_DEL_PEDDING_TXN  = "$TRANS_REPORT.DEL_PEDDING_TXN"
    const val TRANS_VIEW_FAILED_TXN  = "$TRANS_REPORT.VIEW_FAILED_TXN"
    const val TRANS_DEL_FAILED_TXN   = "$TRANS_REPORT.DEL_FAILED_TXN"
    const val TRANS_VIEW_SYSTEM_LOG  = "$TRANS_REPORT.VIEW_SYSTEM_LOG"

    // system transaction page
    const val TRANS_COMM_SETTING     = "$HOME_SETTING.COMM_SETTING"
    const val TRANS_PARAM_SETTING    = "$HOME_SETTING.PARAM_SETTING"
    const val TRANS_TOKEN_REQ        = "$HOME_SETTING.TOKEN_REQ"
    const val TRANS_SYS_MGMT         = "$HOME_SETTING.SYS_MGMT"

    // system management transaction page
    const val TRANS_REBOOT_SYSTEM         = "$TRANS_SYS_MGMT.REBOOT_SYSTEM"
    const val TRANS_UPDATE_SYSTEM_PROGRAM = "$TRANS_SYS_MGMT.UPDATE_SYSTEM_PROGRAM"
    const val TRANS_AUDIBLE_ALARM         = "$TRANS_SYS_MGMT.AUDIBLE_ALARM"
    const val TRANS_HOUSE_KEEPING         = "$TRANS_SYS_MGMT.HOUSE_KEEPING"
    const val TRANS_DUMP_SYSTEM_LOG       = "$TRANS_SYS_MGMT.DUMP_SYSTEM_LOG"
    const val TRANS_DEL_SYSTEM_LOG        = "$TRANS_SYS_MGMT.DEL_SYSTEM_LOG"

    //--------------------------------------------------------------------------------

    const val MAX_CATEGORY_ROW_SIZE = 2
    const val MAX_AMOUNT_LEN = 12

    const val USER_OPER_TIMEOUT       = 60    // USER TIMEOUT (SEC)
    const val USER_OPER_TIMEOUT_1     = 1     // SEC
    const val USER_OPER_TIMEOUT_SHORT = 3     // SEC
    const val USER_OPER_TIMEOUT_5     = 5     // SEC
    const val USER_OPER_TIMEOUT_10    = 10
    const val USER_OPER_TIMEOUT_30    = 30


    // Image ID
    const val DISP_IMAGE_ID_NONE  = 0
    const val DISP_IMAGE_ID_OK    = 1
    const val DISP_IMAGE_ID_ERROR = 2


    // user and dialog control flags
    const val  USER_PRESS_OK_BUTTON          = 1001
    const val  USER_PRESS_CANCEL_BUTTON      = 1002
    const val  USER_PRESS_NATIVE_BACK_BUTTON = 1003
    const val  USER_PRESS_TIMEOUT            = 1004

    // action type
    const val TRANS_TYPE            = "TRANS_TYPE"
    const val ACTION_INIT                     = 1
    const val ACTION_FINISH                   = 2
    const val ACTION_BACK                     = 3
    const val ACTION_INPUT_AMOUNT             = 4
    const val ACTION_INPUT_STRING             = 5
    const val ACTION_DISP_MSG                 = 6
    const val ACTION_CONFIRM_MSG              = 7
    const val ACTION_CONFIRM_DIALOG           = 8
    const val ACTION_DISP_CARD_ENTRY          = 9
    const val ACTION_DISP_DETAIL_RESULT       = 10
    const val ACTION_DISP_APPL_SELECT         = 11
    const val ACTION_DISP_SELECT_ACCOUNT_TYPE = 12
    const val ACTION_ERROR_MSG = 13


    // amount display format
    const val GA_LEADSIGN     = 0x10
    const val GA_DCC          = 0x20
    const val GA_NEGATIVE     = 0x40   // GA_NEGATIVE  : display negative sign: "SGD-123,45.67"
    const val GA_SEPARATOR    = 0x80   // GA_SEPARATOR : use ',' to separater large amount: "HKD$12,345.67"

    // input string format
    const val NUM_IN		= 0x01	// Numeric input
    const val ALPHA_IN		= 0x02	// Numeric / Alpha input
    const val PASS_IN		= 0x04	// input with echo '*'
    const val CARRY_IN		= 0x08	//
    const val ECHO_IN		= 0x10	// echo input data
    const val DOT2_IN		= 0x20	// 0.00
    const val AUTO_FONT		= 0x80	//

    // CVM Result status
    const val CVM_TS_NO_CVM_REQUIRED        =  0x0001
    const val CVM_TS_PIN_VERIFIED           =  0x0002
    const val CVM_TS_PIN_VERIFIED_SIGNATURE =  0x0004
    const val CVN_TS_SIGNATURE              =  0x0008

}
