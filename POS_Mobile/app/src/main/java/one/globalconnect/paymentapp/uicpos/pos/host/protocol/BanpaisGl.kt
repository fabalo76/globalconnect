package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocol

// Keep wire behavior identical to ISSWITCH until the GL-specific contract diverges.
class BanpaisGl : HostProtocol by Isswitch()
