package one.globalconnect.paymentapp.uicpos.pos.transceiver

import android.util.Base64
import android.util.Log
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.host.toHexString
import one.globalconnect.paymentapp.uicpos.pos.util.TimerThread
import one.globalconnect.paymentapp.util.LogSanitizer
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.KeyStore
import javax.net.ssl.*


class SSLClient : BaseSocketClient {

    private var TAG = this::class.java.name

    private var port = 0
    private var ip: String? = null
    private var connectStatus : Boolean = false
    private var transceiver: SSLSocketTransceiver? = null
    private val provideKeys = false

    private var recvTimeoutSec    = 0  ///< sec
    private var connectTimeoutSec = 0   ///< sec
    private var sendTimeoutSec    = 0  ///< sec

    private var isError     = false
    private var isSendMsg   = false
    private var isConnected = false
    private var isStartRun  = false

    private lateinit var recvListener: SocketClientRecvMsgListener<ErrCode, String>

    private val trustManager = TLSTrustManager()


    /**
     * Defines the keystore contents for the client, BKS version. Holds just a
     * single self-generated key. The subject name is "Test Client".
     */
    private val CLIENT_KEYS_BKS =
        "AAAAAQAAABT4Rka6fxbFps98Y5k2VilmbibNkQAABfQEAAVteWtleQAAARpYl+POAAAAAQAFWC41" +
                "MDkAAAJNMIICSTCCAbKgAwIBAgIESEfU9TANBgkqhkiG9w0BAQUFADBpMQswCQYDVQQGEwJVUzET" +
                "MBEGA1UECBMKQ2FsaWZvcm5pYTEMMAoGA1UEBxMDTVRWMQ8wDQYDVQQKEwZHb29nbGUxEDAOBgNV" +
                "BAsTB0FuZHJvaWQxFDASBgNVBAMTC1Rlc3QgQ2xpZW50MB4XDTA4MDYwNTExNTg0NVoXDTA4MDkw" +
                "MzExNTg0NVowaTELMAkGA1UEBhMCVVMxEzARBgNVBAgTCkNhbGlmb3JuaWExDDAKBgNVBAcTA01U" +
                "VjEPMA0GA1UEChMGR29vZ2xlMRAwDgYDVQQLEwdBbmRyb2lkMRQwEgYDVQQDEwtUZXN0IENsaWVu" +
                "dDCBnzANBgkqhkiG9w0BAQEFAAOBjQAwgYkCgYEApUvmWsQDHPpbDKK13Yez2/q54tTOmRml/qva" +
                "2K6dZjkjSTW0iRuk7ztaVEvdJpfVIDv1oBsCI51ttyLHROy1epjF+GoL74mJb7fkcd0VOoSOTjtD" +
                "+3GgZkHPAm5YmUYxiJXqxKKJJqMCTIW46eJaA2nAep9QIwZ14/NFAs4ObV8CAwEAATANBgkqhkiG" +
                "9w0BAQUFAAOBgQCJrCr3hZQFDlLIfsSKI1/w+BLvyf4fubOid0pBxfklR8KBNPTiqjSmu7pd/C/F" +
                "1FR8CdZUDoPflZHCOU+fj5r5KUC1HyigY/tEUvlforBpfB0uCF+tXW4DbUfOWhfMtLV4nCOJOOZg" +
                "awfZLJWBJouLKOp427vDftxTSB+Ks8YjlgAAAqwAAAAU+NH6TtrzjyDdCXm5B6Vo7xX5G4YAAAZx" +
                "EAUkcZtmykn7YdaYxC1jRFJ+GEJpC8nZVg83QClVuCSIS8a5f8Hl44Bk4oepOZsPzhtz3RdVzDVi" +
                "RFfoyZFsrk9F5bDTVJ6sQbb/1nfJkLhZFXokka0vND5AXMSoD5Bj1Fqem3cK7fSUyqKvFoRKC3XD" +
                "FQvhqoam29F1rbl8FaYdPvhhZo8TfZQYUyUKwW+RbR44M5iHPx+ykieMe/C/4bcM3z8cwIbYI1aO" +
                "gjQKS2MK9bs17xaDzeAh4sBKrskFGrDe+2dgvrSKdoakJhLTNTBSG6m+rzqMSCeQpafLKMSjTSSz" +
                "+KoQ9bLyax8cbvViGGju0SlVhquloZmKOfHr8TukIoV64h3uCGFOVFtQjCYDOq6NbfRvMh14UVF5" +
                "zgDIGczoD9dMoULWxBmniGSntoNgZM+QP6Id7DBasZGKfrHIAw3lHBqcvB5smemSu7F4itRoa3D8" +
                "N7hhUEKAc+xA+8NKmXfiCBoHfPHTwDvt4IR7gWjeP3Xv5vitcKQ/MAfO5RwfzkYCXQ3FfjfzmsE1" +
                "1IfLRDiBj+lhQSulhRVStKI88Che3M4JUNGKllrc0nt1pWa1vgzmUhhC4LSdm6trTHgyJnB6OcS9" +
                "t2furYjK88j1AuB4921oxMxRm8c4Crq8Pyuf+n3YKi8Pl2BzBtw++0gj0ODlgwut8SrVj66/nvIB" +
                "jN3kLVahR8nZrEFF6vTTmyXi761pzq9yOVqI57wJGx8o3Ygox1p+pWUPl1hQR7rrhUbgK/Q5wno9" +
                "uJk07h3IZnNxE+/IKgeMTP/H4+jmyT4mhsexJ2BFHeiKF1KT/FMcJdSi+ZK5yoNVcYuY8aZbx0Ef" +
                "lHorCXAmLFB0W6Cz4KPP01nD9YBB4olxiK1t7m0AU9zscdivNiuUaB5OIEr+JuZ6dNw="

    /**
     * Defines the password for the keystore.
     */
    private val PASSWORD = "android"

    /**
     * Implements basically a dummy TrustManager. It stores the certificate
     * chain it sees, so it can later be queried.
     */
    internal class TLSTrustManager : X509TrustManager {

        private lateinit var chain: Array<out java.security.cert.X509Certificate>
        var authType: String? = null

        override fun checkClientTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?
        ) {
            if (chain != null) {
                this.chain = chain
            }
            this.authType = authType
        }

        override fun checkServerTrusted(
            chain: Array<out java.security.cert.X509Certificate>?,
            authType: String?
        ) {
            if (chain != null) {
                this.chain = chain
            }
            this.authType = authType
        }

        override fun getAcceptedIssuers(): Array<out java.security.cert.X509Certificate?> {
            return arrayOfNulls<java.security.cert.X509Certificate>(0)
        }


        fun getChain(): Array<out java.security.cert.X509Certificate> {
            return chain
        }

        fun recordedChain(): Array<out java.security.cert.X509Certificate>? {
            return if (this::chain.isInitialized) chain else null
        }
    }


    /**
     * Loads a keystore from a base64-encoded String. Returns the KeyManager[]
     * for the result.
     */
    @Throws(java.lang.Exception::class)
    private fun getKeyManagers(keys: String): Array<KeyManager?>? {
        val bytes: ByteArray = Base64.decode(keys.toByteArray(),  Base64.DEFAULT)
        val inputStream: InputStream = ByteArrayInputStream(bytes)
        val keyStore: KeyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(inputStream, PASSWORD.toCharArray())
        inputStream.close()
        val algorithm: String = KeyManagerFactory.getDefaultAlgorithm()
        val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance(algorithm)
        keyManagerFactory.init(keyStore, PASSWORD.toCharArray())
        return keyManagerFactory.getKeyManagers()
    }


    fun run() {

        try {
            val keyManagers: Array<KeyManager?>? =
                if (provideKeys) getKeyManagers(CLIENT_KEYS_BKS) else null
            val trustManagers: Array<TrustManager> = arrayOf<TrustManager>(
                trustManager
            )

            val sslContext: SSLContext = SSLContext.getInstance("TLSv1.2")
            sslContext.init(keyManagers, trustManagers, null)

            val ssLSocket: SSLSocket = sslContext.socketFactory.createSocket() as SSLSocket

            val remoteaddr: SocketAddress = InetSocketAddress(this.ip, this.port)
            Log.d(TAG, "Opening TLS socket to $ip:$port connectTimeout=${connectTimeoutSec}s recvTimeout=${recvTimeoutSec}s")
            ssLSocket.soTimeout = recvTimeoutSec * 1000 // receive timeout
            ssLSocket.connect(remoteaddr, connectTimeoutSec * 1000) ///< connect timeout
            ssLSocket.startHandshake();
            Log.d(TAG, "TLS handshake complete protocol=${ssLSocket.session.protocol} cipher=${ssLSocket.session.cipherSuite}")
            if (BuildConfig.ENABLE_SSL_DEBUG_LOGS) {
                val recordedChain = trustManager.recordedChain()
                if (recordedChain != null) {
                    recordedChain.forEachIndexed { index, certificate ->
                        Log.d(
                            TAG,
                            "TLS trust chain[$index] subject=${certificate.subjectDN} issuer=${certificate.issuerDN} serial=${certificate.serialNumber}"
                        )
                    }
                } else {
                    Log.d(TAG, "TLS trust manager did not record peer certificates")
                }
                trustManager.authType?.let { Log.d(TAG, "TLS authentication type=$it") }
            }
            connectStatus = false

                transceiver = object : SSLSocketTransceiver(ssLSocket) {
                    override fun onReceive(addr: InetAddress?, s: ByteArray?, recvLen: Int) {
                        if (recvLen > 0 && s != null) {
                            val str = String(s, 0, recvLen)
                            recvListener.onRecvMsg(ErrCode.NO_ERR, str)
                        }
                    }

                override fun onDisconnect(addr: InetAddress?) {
                    connectStatus = false
                    isConnected = false
                    isError = true
                }

                override fun onReceiveFailed(addr: InetAddress?) {
                    recvListener.onRecvMsg(ErrCode.ERR_COMM_RECEIVE_FAIL, "" )
                }

                override fun onReceiveTimeout(addr: InetAddress?) {
                    recvListener.onRecvMsg(ErrCode.ERR_COMM_RECEIVE_TIMEOUT, "" )
                }

                override fun onProcessing(addr: InetAddress?) {
                    isSendMsg = true
                }

                override fun onSendFailed(addr: InetAddress?) {
                    isError = true
                }

                override fun onConnect(addr: InetAddress?) {
                    isConnected = true
                    connectStatus = true
                }

                override fun onConnectFailed() {
                    connectStatus = false
                    isConnected = false
                    isError = true
                }
            }
            isStartRun = true

        } catch (e: Exception) {
            e.printStackTrace()
            Log.d(TAG, "Socket Thread Run Exception")
            connectStatus = false
            isConnected = false
            isError = true
        }
    }


    fun setRecvTimeout(recvTimeout: Int) {
        this.recvTimeoutSec = recvTimeout
    }

    fun setConnectTimeout(connTimeout: Int) {
        this.connectTimeoutSec = connTimeout
    }

    override fun setRecvMsgListener(listener: SocketClientRecvMsgListener<ErrCode, String>) {
        this.recvListener = listener
    }

    override fun init(ipAddr: String, ipPort: Int, connTimeout: Int, sendTimeout: Int, recvTimeout: Int ) {
        this.ip   = ipAddr
        this.port = ipPort

        if(connTimeout > 0 ) {
            this.connectTimeoutSec = connTimeout
        }

        if(sendTimeout > 0) {
            this.sendTimeoutSec = sendTimeout
        }

        if(recvTimeout > 0) {
            this.recvTimeoutSec = recvTimeout
        }

    }

    override fun start() {

        isStartRun = false

        Thread {
            this.run()
        }.start()
    }

    override fun connect(): ErrCode {

        var ret: ErrCode = ErrCode.NO_ERR

        Log.d(TAG, "Connect to " + this.ip + ":" + this.port)
        Log.d(TAG, "Socket timeouts -> connect=${connectTimeoutSec}s send=${sendTimeoutSec}s recv=${recvTimeoutSec}s")
        isError = false

        if(this.connectStatus) {
            return ret
        }

        while(true) {
            // waiting socket thread create
            Thread.sleep(1)
            if(isStartRun && transceiver != null ) {

                // socket thread created finish
                break
            }

            if(isError) {
                // socket thread created failed
                ret = ErrCode.ERR_COMM_OPEN_FAIL
                return ret
            }

        }

        // transceiver thread start
        transceiver!!.start()

        // check connection
        while(true) {
            Thread.sleep(1)
            if( connectStatus ) {
                Log.d(TAG, "Connect OK")
                break
            }

            if(isError) {
                ret = ErrCode.ERR_COMM_CONNECT_FAIL
                break
            }
        }

        return ret
    }



    override fun disconnect() : ErrCode {

        var ret: ErrCode = ErrCode.NO_ERR

        isError = false

        if( transceiver == null )
            return ErrCode.ERR_COMM_OPEN_FAIL

        if(!this.connectStatus)
            return ret

        Log.d(TAG, "Disconnect Start")

        if (transceiver != null) {
            transceiver!!.stop()
            transceiver = null
        }

        //-----------------------------------------------------

        // check disconnect
        while(true) {
            Thread.sleep(1)
            if(!this.connectStatus) {
                Log.d(TAG, "Disconnect OK")
                break
            }

            if(isError) {
                ret = ErrCode.ERR_COMM_DISCONNECT_FAIL
                break
            }
        }

        return ret
    }

    override fun sendMsg(data: String ): ErrCode {

        var ret: ErrCode = ErrCode.NO_ERR

        isSendMsg = false
        isError = false

        if(transceiver == null) {
            isError = true
            return ErrCode.ERR_COMM_OPEN_FAIL
        }

        // send timer
        var timerThread = TimerThread()

        timerThread.startTimer( this.sendTimeoutSec )

        // send data
        do{
            if( transceiver!!.send( data.toByteArray() ) )
                break

            if( timerThread.isTimeout() == true ) {
                return ErrCode.ERR_COMM_SEND_FAIL
            }

        }while(true)


        // check send
        while(true) {
            Thread.sleep(2)
            if(isSendMsg) {
                Log.d(TAG, "Send OK")
                Log.d(TAG, "Sent data: ${LogSanitizer.sanitizeMessage(data)}")
                val hexPayload = LogSanitizer.sanitizeHexPayload { data.toByteArray().toHexString() }
                Log.d(TAG, "Sent data HEX: $hexPayload")
                break
            }

            if(isError) {
                Log.d(TAG, "Send failed")
                ret = ErrCode.ERR_COMM_SEND_FAIL
                break
            }

            if( timerThread.isTimeout() == true ) {
                Log.d(TAG, "Send Timeout")
                ret = ErrCode.ERR_COMM_SEND_FAIL
                break
            }

        }

        // stop timer
        timerThread.stopTimer()


        return ret
    }

}