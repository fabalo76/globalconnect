package one.globalconnect.paymentapp.uicpos.pos.transceiver

import android.util.Log
import one.globalconnect.paymentapp.BuildConfig
import one.globalconnect.paymentapp.uicpos.pos.errcode.ErrCode
import one.globalconnect.paymentapp.uicpos.pos.host.toHexString
import one.globalconnect.paymentapp.uicpos.pos.util.TimerThread
import one.globalconnect.paymentapp.util.LogSanitizer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

class TcpClient : BaseSocketClient {

    private var TAG = this::class.java.name

    private var port = 0
    private var ip: String? = null
    private var connectStatus : Boolean = false
    private var transceiver: TcpSocketTransceiver? = null

    private var recvTimeoutSec    = 0  ///< sec
    private var connectTimeoutSec = 0   ///< sec
    private var sendTimeoutSec    = 0  ///< sec

    private var isError     = false
    private var isSendMsg   = false
    private var isConnected = false
    private var isStartRun  = false

    private lateinit var recvListener: SocketClientRecvMsgListener<ErrCode, String>

    fun run() {

        try {
            val socket: Socket = SocketFactory.getDefault().createSocket()
            val remoteaddr: SocketAddress = InetSocketAddress(this.ip, this.port)
            Log.d(TAG, "Opening TCP socket to $ip:$port connectTimeout=${connectTimeoutSec}s recvTimeout=${recvTimeoutSec}s")
            socket.connect(remoteaddr, connectTimeoutSec * 1000) ///< connect timeout
            socket.soTimeout = recvTimeoutSec * 1000 // receive timeout

            connectStatus = false

            transceiver = object : TcpSocketTransceiver(socket) {
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
            Log.d("TCPSocketTransceiver", "Loop in send")

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
            Log.d("TCPSocketTransceiver", "Loop in check send")

        }

        // stop timer
        timerThread.stopTimer()
        Log.d("TCPSocketTransceiver", "Send message complete")


        return ret
    }

}