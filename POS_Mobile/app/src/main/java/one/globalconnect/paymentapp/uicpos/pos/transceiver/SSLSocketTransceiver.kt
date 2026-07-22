package one.globalconnect.paymentapp.uicpos.pos.transceiver


import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress

import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket

abstract class SSLSocketTransceiver(socket: SSLSocket) : Runnable {

    var socket: SSLSocket? = socket
    var addr: InetAddress? = socket.inetAddress
    var inStream: DataInputStream? = null
    var outStream: DataOutputStream? = null
    var runFlag = false
    val REV_BUFFER_SIZE = 1024 * 512

    fun getInetAddress(): InetAddress? {
        return addr
    }

    fun start() {
        Log.d("Socket", "Starting socket")
        runFlag = true
        try {
            inStream = DataInputStream(socket!!.getInputStream())
            outStream = DataOutputStream(socket!!.getOutputStream())
            onConnect(addr)
            Thread(this).start()
        } catch (e: IOException) {
            e.printStackTrace()
            runFlag = false
            onConnectFailed()
        }
    }

    fun stop() {
        runFlag = false
        try {
            if (!socket!!.isClosed) {
                socket!!.shutdownInput()
            }

            inStream!!.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun send(s: ByteArray?): Boolean {
        if (outStream != null) {
            try {
                outStream!!.write(s)
                //out.writeUTF(s);
                outStream!!.flush()
                onProcessing(addr)
                return true
            } catch (timeout: SocketTimeoutException) {
                timeout.printStackTrace()

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        onSendFailed(addr)
        return false
    }

    override fun run() {
        val timeoutInMillis = socket?.soTimeout?.takeIf { it > 0 }?.toLong() ?: 0L
        val enforceTimeout = timeoutInMillis > 0
        val startTime = System.currentTimeMillis()
        while (runFlag) {
            try {
                //final String s = in.readUTF();
                val bufferSize = REV_BUFFER_SIZE
                val s = ByteArray(bufferSize)
                val count: Int = inStream!!.read(s)
                onReceive(addr, s, count)

                if (socket!!.isClosed) {
                    throw IOException("socket has been closed")
                }

                if (enforceTimeout && System.currentTimeMillis() - startTime > timeoutInMillis) {
                    throw SocketTimeoutException("Data receive window has exceeded timeout limit")
                }
            } catch (timeout: SocketTimeoutException) {
                if (runFlag != false) {
                    runFlag = false
                    onReceiveTimeout(addr)
                }

                timeout.printStackTrace()

            } catch (e: IOException) {
                // connection broken
                if (runFlag != false) {
                    runFlag = false
                    onReceiveFailed(addr)
                }

                e.printStackTrace()
            }
        }

        // disconnect
        try {
            inStream!!.close()
            outStream!!.close()
            socket!!.close()
            inStream = null
            outStream = null
            socket = null
        } catch (e: IOException) {
            e.printStackTrace()
        }
        onDisconnect(addr)
    }

    abstract fun onReceive(addr: InetAddress?, s: ByteArray?, recvLen: Int)

    abstract fun onDisconnect(addr: InetAddress?)

    abstract fun onReceiveFailed(addr: InetAddress?)

    abstract fun onReceiveTimeout(addr: InetAddress?)

    abstract fun onProcessing(addr: InetAddress?)

    abstract fun onSendFailed(addr: InetAddress?)

    abstract fun onConnect(addr: InetAddress?)

    abstract fun onConnectFailed()
}