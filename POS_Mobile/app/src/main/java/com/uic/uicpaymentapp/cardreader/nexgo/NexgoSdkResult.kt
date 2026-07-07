package com.uic.uicpaymentapp.cardreader.nexgo

/**
 * Mirror of com.nexgo.oaf.apiv3.SdkResult with human-readable message mapping.
 *
 * Source: nexgo-smartpos-sdk-v3.08.002_20240410.aar (SdkResult.class)
 */
object NexgoSdkResult {

    // ── General ───────────────────────────────────────────────────────────────
    const val Success                              =     0
    const val Fail                                 =    -1
    const val Param_In_Invalid                     =    -2
    const val TimeOut                              =    -3
    const val Device_Not_Ready                     =    -4
    const val NotSupport                           =    -5
    const val Cancel                               =    -6

    // ── Printer (-1000) ───────────────────────────────────────────────────────
    const val Printer_Print_Fail                   = -1001
    const val Printer_AddPrnStr_Fail               = -1002
    const val Printer_AddImg_Fail                  = -1003
    const val Printer_Busy                         = -1004
    const val Printer_PaperLack                    = -1005
    const val Printer_Wrong_Package                = -1006
    const val Printer_Fault                        = -1007
    const val Printer_TooHot                       = -1008
    const val Printer_UnFinished                   = -1009
    const val Printer_NoDevice                     = -1010
    const val Printer_Other_Error                  = -1999

    // ── Scanner (-2000) ───────────────────────────────────────────────────────
    const val Scanner_Customer_Exit                = -2001
    const val Scanner_Other_Error                  = -2999

    // ── Face Detect (-3000) ───────────────────────────────────────────────────
    const val Face_Detect_Customer_Exit            = -3001
    const val Face_Detect_Call_Frequently          = -3002
    const val Face_Detect_Sdk_Not_Load             = -3003
    const val Face_Detect_Service_Unbind           = -3004
    const val Face_Detect_Other_Error              = -3999

    // ── Serial Port (-4000) ───────────────────────────────────────────────────
    const val SerialPort_Connect_Fail              = -4001
    const val SerialPort_Send_Fail                 = -4002
    const val SerialPort_Fd_Error                  = -4003
    const val SerialPort_Port_Not_Open             = -4004
    const val SerialPort_DisConnect_Fail           = -4005
    const val SerialPort_Timeout_Receiving_Data    = -4006
    const val SerialPort_Invalid_Communication_Parameter = -4007
    const val SerialPort_DisConnected              = -4008
    const val SerialPort_Other_Error               = -4999

    // ── Mag Card Reader (-5000) ───────────────────────────────────────────────
    const val MagCardReader_NoPermission_Error     = -5001
    const val MagCardReader_Other_Error            = -5999

    // ── ICC Card Reader (-6000) ───────────────────────────────────────────────
    const val IccCardReader_Other_Error            = -6999

    // ── PIN Pad (-7000) ───────────────────────────────────────────────────────
    const val PinPad_No_Key_Error                  = -7001
    const val PinPad_KeyIdx_Error                  = -7002
    const val PinPad_No_Pin_Input                  = -7003
    const val PinPad_Input_Cancel                  = -7004
    const val PinPad_Key_Len_Error                 = -7008
    const val PinPad_Input_Timeout                 = -7009
    const val PinPad_Open_Or_Close_Error           = -7010
    const val PinPad_Deal_Error                    = -7011
    const val PinPad_Secure_Entry_Reset_Pwd        = -7012
    const val PinPad_Secure_Entry_Verify_Pwd       = -7013
    const val PinPad_Secure_Entry_Pwd_Incorrect    = -7014
    const val PinPad_Secure_Entry_Pwd_Inconsistency = -7015
    const val PinPad_Other_Error                   = -7999

    // ── EMV Handler (-8000) ───────────────────────────────────────────────────
    const val Emv_Other_Interface                  = -8001
    const val Emv_Qpboc_Offline                    = -8002
    const val Emv_Qpboc_Online                     = -8003
    const val Emv_Pboc_Online                      = -8004
    const val Emv_MSD_Online                       = -8005
    const val Emv_Ec_Accept                        = -8006
    const val Emv_Offline_Accept                   = -8007
    const val Emv_Card_Removed                     = -8008
    const val Emv_Command_Fail                     = -8009
    const val Emv_Card_Block                       = -8010
    const val Emv_PARA_ERR                         = -8011
    const val Emv_Candidatelist_Empty              = -8012
    const val Emv_App_Block                        = -8013
    const val Emv_FallBack                         = -8014
    const val Emv_Auth_Fail                        = -8015
    const val Emv_App_Ineffect                     = -8016
    const val Emv_App_Expired                      = -8017
    const val Emv_Cvm_Fail                         = -8018
    const val Emv_Online                           = -8019
    const val Emv_Cancel                           = -8020
    const val Emv_Declined                         = -8021
    const val Emv_Arpc_Fail                        = -8022
    const val Emv_Script_Fail                      = -8023
    const val Emv_App_NoAccept                     = -8024
    const val Emv_Offline_Declined                 = -8025
    const val Emv_Success_Arpc_Fail                = -8026
    const val Emv_Plz_See_Phone                    = -8027
    const val Emv_Terminate                        = -8028
    const val Emv_Communicate_Timeout              = -8029
    const val Emv_USE_OTHER_CARD                   = -8030
    const val Emv_CTLS_Select_App                  = -8031
    const val Emv_CTLS_EndApplication              = -8032
    const val Emv_CTLS_Torn                        = -8033
    const val Emv_Other_Error                      = -8999

    // ── Card Handler / ICC (-10000) ───────────────────────────────────────────
    const val Icc_PullOut_Card                     = -10101
    const val Icc_Parity_Err                       = -10102
    const val Icc_Channel_Err                      = -10103
    const val Icc_Data_Len_TooLong                 = -10104
    const val Icc_Protocol_Err                     = -10105
    const val Icc_No_Reset_Card                    = -10106
    const val Icc_Not_Call                         = -10107
    const val Icc_Other_Error                      = -10199

    // ── Card Handler / PICC (contactless) (-10200) ────────────────────────────
    const val Picc_Not_Open                        = -10201
    const val Picc_Not_Searched_Card               = -10202
    const val Picc_Card_Too_Many                   = -10203
    const val Picc_Protocol_Data_Err               = -10204
    const val Picc_Card_No_Activation              = -10205
    const val Picc_Muti_Card_Err                   = -10206
    const val Picc_Protocol_Err                    = -10207
    const val Picc_Io_Err                          = -10208
    const val Picc_Card_Sense_Err                  = -10209
    const val Picc_Card_Status_Err                 = -10210
    const val Picc_Not_Call                        = -10211
    const val Picc_Other_Error                     = -10299

    // ── M1 Card (-10300) ──────────────────────────────────────────────────────
    const val M1Card_Verify_Err                    = -10301
    const val M1Card_Other_Error                   = -10399

    // ── Platform (-20000) ─────────────────────────────────────────────────────
    const val Platform_Install_Already_Exists      = -20101
    const val Platform_Install_Invalid_Apk         = -20102
    const val Platform_Install_Invalid_Uri         = -20103
    const val Platform_Install_Insufficient_Storage = -20104
    const val Platform_Install_Duplicate_Package   = -20105
    const val Platform_Install_No_Shared_User      = -20106
    const val Platform_Install_Update_Incompatible = -20107
    const val Platform_Install_Shared_User_Incompatible = -20108
    const val Platform_Install_Missing_Shared_Library = -20109
    const val Platform_Install_Replace_Delete_Failed = -20110
    const val Platform_Install_Dexopt              = -20111
    const val Platform_Uninstall_Internal_Error    = -20201
    const val Platform_Uninstall_Device_Policy_Manager = -20202
    const val Platform_Uninstall_User_Restricted   = -20203
    const val Platform_Uninstall_Owner_Blocked     = -20204
    const val Platform_Uninstall_Aborted           = -20205
    const val Platform_Update_No_Match             = -20301
    const val Platform_Update_Failed               = -20302

    // ── SDK name lookup (for logcat) ──────────────────────────────────────────

    /** Returns the SDK constant name for a result code, e.g. "Emv_Candidatelist_Empty". */
    fun sdkName(code: Int): String = when (code) {
        Success                              -> "Success"
        Fail                                 -> "Fail"
        Param_In_Invalid                     -> "Param_In_Invalid"
        TimeOut                              -> "TimeOut"
        Device_Not_Ready                     -> "Device_Not_Ready"
        NotSupport                           -> "NotSupport"
        Cancel                               -> "Cancel"
        Printer_Print_Fail                   -> "Printer_Print_Fail"
        Printer_AddPrnStr_Fail               -> "Printer_AddPrnStr_Fail"
        Printer_AddImg_Fail                  -> "Printer_AddImg_Fail"
        Printer_Busy                         -> "Printer_Busy"
        Printer_PaperLack                    -> "Printer_PaperLack"
        Printer_Wrong_Package                -> "Printer_Wrong_Package"
        Printer_Fault                        -> "Printer_Fault"
        Printer_TooHot                       -> "Printer_TooHot"
        Printer_UnFinished                   -> "Printer_UnFinished"
        Printer_NoDevice                     -> "Printer_NoDevice"
        Printer_Other_Error                  -> "Printer_Other_Error"
        Scanner_Customer_Exit                -> "Scanner_Customer_Exit"
        Scanner_Other_Error                  -> "Scanner_Other_Error"
        Face_Detect_Customer_Exit            -> "Face_Detect_Customer_Exit"
        Face_Detect_Call_Frequently          -> "Face_Detect_Call_Frequently"
        Face_Detect_Sdk_Not_Load             -> "Face_Detect_Sdk_Not_Load"
        Face_Detect_Service_Unbind           -> "Face_Detect_Service_Unbind"
        Face_Detect_Other_Error              -> "Face_Detect_Other_Error"
        SerialPort_Connect_Fail              -> "SerialPort_Connect_Fail"
        SerialPort_Send_Fail                 -> "SerialPort_Send_Fail"
        SerialPort_Fd_Error                  -> "SerialPort_Fd_Error"
        SerialPort_Port_Not_Open             -> "SerialPort_Port_Not_Open"
        SerialPort_DisConnect_Fail           -> "SerialPort_DisConnect_Fail"
        SerialPort_Timeout_Receiving_Data    -> "SerialPort_Timeout_Receiving_Data"
        SerialPort_Invalid_Communication_Parameter -> "SerialPort_Invalid_Communication_Parameter"
        SerialPort_DisConnected              -> "SerialPort_DisConnected"
        SerialPort_Other_Error               -> "SerialPort_Other_Error"
        MagCardReader_NoPermission_Error     -> "MagCardReader_NoPermission_Error"
        MagCardReader_Other_Error            -> "MagCardReader_Other_Error"
        IccCardReader_Other_Error            -> "IccCardReader_Other_Error"
        PinPad_No_Key_Error                  -> "PinPad_No_Key_Error"
        PinPad_KeyIdx_Error                  -> "PinPad_KeyIdx_Error"
        PinPad_No_Pin_Input                  -> "PinPad_No_Pin_Input"
        PinPad_Input_Cancel                  -> "PinPad_Input_Cancel"
        PinPad_Key_Len_Error                 -> "PinPad_Key_Len_Error"
        PinPad_Input_Timeout                 -> "PinPad_Input_Timeout"
        PinPad_Open_Or_Close_Error           -> "PinPad_Open_Or_Close_Error"
        PinPad_Deal_Error                    -> "PinPad_Deal_Error"
        PinPad_Secure_Entry_Reset_Pwd        -> "PinPad_Secure_Entry_Reset_Pwd"
        PinPad_Secure_Entry_Verify_Pwd       -> "PinPad_Secure_Entry_Verify_Pwd"
        PinPad_Secure_Entry_Pwd_Incorrect    -> "PinPad_Secure_Entry_Pwd_Incorrect"
        PinPad_Secure_Entry_Pwd_Inconsistency -> "PinPad_Secure_Entry_Pwd_Inconsistency"
        PinPad_Other_Error                   -> "PinPad_Other_Error"
        Emv_Other_Interface                  -> "Emv_Other_Interface"
        Emv_Qpboc_Offline                    -> "Emv_Qpboc_Offline"
        Emv_Qpboc_Online                     -> "Emv_Qpboc_Online"
        Emv_Pboc_Online                      -> "Emv_Pboc_Online"
        Emv_MSD_Online                       -> "Emv_MSD_Online"
        Emv_Ec_Accept                        -> "Emv_Ec_Accept"
        Emv_Offline_Accept                   -> "Emv_Offline_Accept"
        Emv_Card_Removed                     -> "Emv_Card_Removed"
        Emv_Command_Fail                     -> "Emv_Command_Fail"
        Emv_Card_Block                       -> "Emv_Card_Block"
        Emv_PARA_ERR                         -> "Emv_PARA_ERR"
        Emv_Candidatelist_Empty              -> "Emv_Candidatelist_Empty"
        Emv_App_Block                        -> "Emv_App_Block"
        Emv_FallBack                         -> "Emv_FallBack"
        Emv_Auth_Fail                        -> "Emv_Auth_Fail"
        Emv_App_Ineffect                     -> "Emv_App_Ineffect"
        Emv_App_Expired                      -> "Emv_App_Expired"
        Emv_Cvm_Fail                         -> "Emv_Cvm_Fail"
        Emv_Online                           -> "Emv_Online"
        Emv_Cancel                           -> "Emv_Cancel"
        Emv_Declined                         -> "Emv_Declined"
        Emv_Arpc_Fail                        -> "Emv_Arpc_Fail"
        Emv_Script_Fail                      -> "Emv_Script_Fail"
        Emv_App_NoAccept                     -> "Emv_App_NoAccept"
        Emv_Offline_Declined                 -> "Emv_Offline_Declined"
        Emv_Success_Arpc_Fail                -> "Emv_Success_Arpc_Fail"
        Emv_Plz_See_Phone                    -> "Emv_Plz_See_Phone"
        Emv_Terminate                        -> "Emv_Terminate"
        Emv_Communicate_Timeout              -> "Emv_Communicate_Timeout"
        Emv_USE_OTHER_CARD                   -> "Emv_USE_OTHER_CARD"
        Emv_CTLS_Select_App                  -> "Emv_CTLS_Select_App"
        Emv_CTLS_EndApplication              -> "Emv_CTLS_EndApplication"
        Emv_CTLS_Torn                        -> "Emv_CTLS_Torn"
        Emv_Other_Error                      -> "Emv_Other_Error"
        Icc_PullOut_Card                     -> "Icc_PullOut_Card"
        Icc_Parity_Err                       -> "Icc_Parity_Err"
        Icc_Channel_Err                      -> "Icc_Channel_Err"
        Icc_Data_Len_TooLong                 -> "Icc_Data_Len_TooLong"
        Icc_Protocol_Err                     -> "Icc_Protocol_Err"
        Icc_No_Reset_Card                    -> "Icc_No_Reset_Card"
        Icc_Not_Call                         -> "Icc_Not_Call"
        Icc_Other_Error                      -> "Icc_Other_Error"
        Picc_Not_Open                        -> "Picc_Not_Open"
        Picc_Not_Searched_Card               -> "Picc_Not_Searched_Card"
        Picc_Card_Too_Many                   -> "Picc_Card_Too_Many"
        Picc_Protocol_Data_Err               -> "Picc_Protocol_Data_Err"
        Picc_Card_No_Activation              -> "Picc_Card_No_Activation"
        Picc_Muti_Card_Err                   -> "Picc_Muti_Card_Err"
        Picc_Protocol_Err                    -> "Picc_Protocol_Err"
        Picc_Io_Err                          -> "Picc_Io_Err"
        Picc_Card_Sense_Err                  -> "Picc_Card_Sense_Err"
        Picc_Card_Status_Err                 -> "Picc_Card_Status_Err"
        Picc_Not_Call                        -> "Picc_Not_Call"
        Picc_Other_Error                     -> "Picc_Other_Error"
        M1Card_Verify_Err                    -> "M1Card_Verify_Err"
        M1Card_Other_Error                   -> "M1Card_Other_Error"
        Platform_Install_Already_Exists      -> "Platform_Install_Already_Exists"
        Platform_Install_Invalid_Apk         -> "Platform_Install_Invalid_Apk"
        Platform_Install_Invalid_Uri         -> "Platform_Install_Invalid_Uri"
        Platform_Install_Insufficient_Storage -> "Platform_Install_Insufficient_Storage"
        Platform_Install_Duplicate_Package   -> "Platform_Install_Duplicate_Package"
        Platform_Install_No_Shared_User      -> "Platform_Install_No_Shared_User"
        Platform_Install_Update_Incompatible -> "Platform_Install_Update_Incompatible"
        Platform_Install_Shared_User_Incompatible -> "Platform_Install_Shared_User_Incompatible"
        Platform_Install_Missing_Shared_Library -> "Platform_Install_Missing_Shared_Library"
        Platform_Install_Replace_Delete_Failed -> "Platform_Install_Replace_Delete_Failed"
        Platform_Install_Dexopt              -> "Platform_Install_Dexopt"
        Platform_Uninstall_Internal_Error    -> "Platform_Uninstall_Internal_Error"
        Platform_Uninstall_Device_Policy_Manager -> "Platform_Uninstall_Device_Policy_Manager"
        Platform_Uninstall_User_Restricted   -> "Platform_Uninstall_User_Restricted"
        Platform_Uninstall_Owner_Blocked     -> "Platform_Uninstall_Owner_Blocked"
        Platform_Uninstall_Aborted           -> "Platform_Uninstall_Aborted"
        Platform_Update_No_Match             -> "Platform_Update_No_Match"
        Platform_Update_Failed               -> "Platform_Update_Failed"
        else                                 -> "Unknown($code)"
    }

    // ── Friendly message lookup ───────────────────────────────────────────────

    /**
     * Returns a user-facing message for a Nexgo SDK result code.
     * Falls back to the numeric code for unmapped values.
     */
    fun friendlyMessage(code: Int): String = when (code) {
        Success                              -> "Success"
        Fail                                 -> "Operation failed"
        Param_In_Invalid                     -> "Invalid parameter"
        TimeOut                              -> "Operation timed out"
        Device_Not_Ready                     -> "Device not ready"
        NotSupport                           -> "Operation not supported"
        Cancel                               -> "Cancelled"

        // Printer
        Printer_Print_Fail                   -> "Print failed"
        Printer_AddPrnStr_Fail               -> "Failed to add print text"
        Printer_AddImg_Fail                  -> "Failed to add print image"
        Printer_Busy                         -> "Printer busy"
        Printer_PaperLack                    -> "Printer out of paper"
        Printer_Wrong_Package                -> "Printer package error"
        Printer_Fault                        -> "Printer hardware fault"
        Printer_TooHot                       -> "Printer overheated"
        Printer_UnFinished                   -> "Print job unfinished"
        Printer_NoDevice                     -> "Printer not available"
        Printer_Other_Error                  -> "Printer error"

        // PIN Pad
        PinPad_No_Key_Error                  -> "PIN key not loaded"
        PinPad_KeyIdx_Error                  -> "Invalid PIN key index"
        PinPad_No_Pin_Input                  -> "No PIN entered"
        PinPad_Input_Cancel                  -> "PIN entry cancelled"
        PinPad_Key_Len_Error                 -> "PIN key length error"
        PinPad_Input_Timeout                 -> "PIN entry timed out"
        PinPad_Open_Or_Close_Error           -> "PIN pad open/close error"
        PinPad_Deal_Error                    -> "PIN pad processing error"
        PinPad_Other_Error                   -> "PIN pad error"

        // EMV — flow states (non-error outcomes)
        Emv_Other_Interface                  -> "Card requires different interface"
        Emv_Qpboc_Offline                    -> "Contactless offline approved"
        Emv_Qpboc_Online                     -> "Contactless online required"
        Emv_Pboc_Online                      -> "Contact chip online required"
        Emv_MSD_Online                       -> "Magnetic stripe online required"
        Emv_Ec_Accept                        -> "Electronic cash accepted"
        Emv_Offline_Accept                   -> "Offline approved"
        Emv_Online                           -> "Online authorization required"
        Emv_Cancel                           -> "Transaction cancelled"
        Emv_Declined                         -> "Transaction declined"
        Emv_Offline_Declined                 -> "Transaction declined offline"
        Emv_Plz_See_Phone                    -> "Please complete on phone"
        Emv_CTLS_Select_App                  -> "Select contactless application"
        Emv_CTLS_EndApplication              -> "Contactless application ended"

        // EMV — card/config errors
        Emv_Card_Removed                     -> "Card removed during transaction"
        Emv_Command_Fail                     -> "Card command failed — try again"
        Emv_Card_Block                       -> "Card is blocked"
        Emv_PARA_ERR                         -> "EMV configuration error"
        Emv_Candidatelist_Empty              -> "Card brand not supported"
        Emv_App_Block                        -> "Card application is blocked"
        Emv_FallBack                         -> "Card requires magnetic stripe fallback"
        Emv_Auth_Fail                        -> "Card authentication failed"
        Emv_App_Ineffect                     -> "Card application not yet active"
        Emv_App_Expired                      -> "Card application expired"
        Emv_Cvm_Fail                         -> "Cardholder verification failed"
        Emv_Arpc_Fail                        -> "Authorization response authentication failed"
        Emv_Script_Fail                      -> "Issuer script failed"
        Emv_App_NoAccept                     -> "Card application not accepted"
        Emv_Success_Arpc_Fail                -> "Approved — response authentication failed"
        Emv_Terminate                        -> "Transaction terminated by card"
        Emv_Communicate_Timeout              -> "Card communication timed out"
        Emv_USE_OTHER_CARD                   -> "Use a different card"
        Emv_CTLS_Torn                        -> "Contactless transaction interrupted — try again"
        Emv_Other_Error                      -> "EMV processing error"

        // ICC (contact chip hardware)
        Icc_PullOut_Card                     -> "Card removed too early"
        Icc_Parity_Err                       -> "Card read error — try again"
        Icc_Channel_Err                      -> "Card channel error"
        Icc_Data_Len_TooLong                 -> "Card data length error"
        Icc_Protocol_Err                     -> "Card protocol error"
        Icc_No_Reset_Card                    -> "Card not responding — try again"
        Icc_Not_Call                         -> "Card reader not initialized"
        Icc_Other_Error                      -> "Card read error"

        // PICC (contactless hardware)
        Picc_Not_Open                        -> "Contactless reader not open"
        Picc_Not_Searched_Card               -> "No contactless card detected"
        Picc_Card_Too_Many                   -> "Multiple contactless cards detected"
        Picc_Protocol_Data_Err               -> "Contactless protocol data error"
        Picc_Card_No_Activation              -> "Contactless card not activated"
        Picc_Muti_Card_Err                   -> "Multiple card conflict — present one card"
        Picc_Protocol_Err                    -> "Contactless protocol error"
        Picc_Io_Err                          -> "Contactless I/O error"
        Picc_Card_Sense_Err                  -> "Contactless sense error"
        Picc_Card_Status_Err                 -> "Contactless card status error"
        Picc_Not_Call                        -> "Contactless reader not initialized"
        Picc_Other_Error                     -> "Contactless read error"

        else -> "Error ($code)"
    }
}
