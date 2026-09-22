package one.globalconnect.paymentapp.uicpos.pos.host.protocol

import one.globalconnect.paymentapp.uicpos.pos.host.HostProtocol

// Keep wire behavior identical to ISSWITCH until the IONET-specific contract diverges.
class BanpaisIo : HostProtocol by Isswitch()
