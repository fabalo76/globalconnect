package com.uic.uicpaymentapp.uicpos.pos.transceiver

import com.uic.uicpaymentapp.uicpos.pos.errcode.ErrCode


interface SocketClientRecvMsgListener<ErrCode, T> {

    fun onRecvMsg(status: ErrCode, msg: T)
}

interface BaseSocketClient {

    fun init(ipAddr: String, ipPort: Int, connTimeout: Int, sendTimeout: Int, recvTimeout: Int )
    fun start()
    fun connect(): ErrCode
    fun disconnect() : ErrCode
    fun sendMsg( data: String ): ErrCode
    fun setRecvMsgListener(listener: SocketClientRecvMsgListener<ErrCode, String>)
}