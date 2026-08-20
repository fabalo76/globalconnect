package one.globalconnect.xtmsagent.params

/**
 * Shared constants for parameter download IPC between xTMSAgent and the payment app.
 *
 * IPC flow — pull (payment app requests):
 *   1. Payment app broadcasts ACTION_REQUEST_PARAMS (explicit, targeting "one.globalconnect.xtmsagent").
 *   2. [ParamReceiver] in xTMSAgent receives it and calls [ParamManager.requestParamDownload].
 *   3. xTMSAgent replies with ACTION_PARAMS_READY or ACTION_PARAMS_FAILED (explicit, targeting
 *      the validated callback package supplied by the requesting payment app).
 *
 * IPC flow — push (TMS server notifies update):
 *   1. TMS sends an MQTT notification with FLAG_PARAM_DOWNLOAD set.
 *   2. [TmsNotificationHandler] calls [ParamManager.requestParamDownload].
 *   3. Same reply flow as above.
 */
object ParamConstants {

    /** Payment app broadcasts this action to request the parameter file from xTMSAgent. */
    const val ACTION_REQUEST_PARAMS = "one.globalconnect.xtmsagent.ACTION_REQUEST_PARAMS"

    /** xTMSAgent broadcasts this to the payment app when new parameters are ready. */
    const val ACTION_PARAMS_READY   = "one.globalconnect.xtmsagent.ACTION_PARAMS_READY"

    /** xTMSAgent broadcasts this to the payment app when a parameter download has failed. */
    const val ACTION_PARAMS_FAILED  = "one.globalconnect.xtmsagent.ACTION_PARAMS_FAILED"

    /** Payment app broadcasts this back after parameters are applied, rejected, or deferred. */
    const val ACTION_PARAMS_RESULT = "one.globalconnect.paymentapp.ACTION_PARAMS_RESULT"

    /**
     * String extra in [ACTION_PARAMS_READY]: a content:// URI string (FileProvider) pointing
     * to the decompressed params JSON file.  The payment app must call
     * `context.grantUriPermission` is already done by xTMSAgent before the broadcast;
     * the receiver should open the URI immediately and not cache it for later.
     */
    const val EXTRA_PARAMS_URI      = "params_uri"

    /** String extra in [ACTION_PARAMS_FAILED]: human-readable error description. */
    const val EXTRA_ERROR_MESSAGE   = "error_message"

    /** Global Connect task identifier propagated through the complete apply pipeline. */
    const val EXTRA_TASK_ID = "taskId"

    /** Explicit xTMSAgent package that must receive [ACTION_PARAMS_RESULT]. */
    const val EXTRA_RESULT_PACKAGE = "resultPackage"

    /** Payment-app result status: completed, failed, or deferred. */
    const val EXTRA_RESULT_STATUS = "status"

    /**
     * String extra in [ACTION_REQUEST_PARAMS]: TMS application identifier requested by
     * the caller, for example "PAYMENT_APP".
     */
    const val EXTRA_APPLICATION_ID  = "applicationId"

    /**
     * String extra in [ACTION_REQUEST_PARAMS]: package that must receive the response.
     * xTMSAgent validates that this package declares both PAY_APP and the parameter receiver.
     */
    const val EXTRA_CLIENT_PACKAGE  = "clientPackage"

    /** Sub-folder inside [Context.filesDir] where the decompressed params JSON is stored. */
    const val PARAMS_FOLDER         = "params"

    /** Filename of the decompressed parameter JSON written inside [PARAMS_FOLDER]. */
    const val PARAMS_FILENAME       = "params.json"
}
