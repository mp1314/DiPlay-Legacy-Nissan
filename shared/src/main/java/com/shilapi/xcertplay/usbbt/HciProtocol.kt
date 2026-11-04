package com.shilapi.xcertplay.usbbt

/**
 * Bluetooth HCI/USB transport protocol constants, packet encoding and parsing.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-testable on the JVM.
 * Based on the Bluetooth Core Specification:
 * - Vol 4, Part B (USB Transport Layer): HCI commands travel as USB control
 *   transfers on endpoint 0 (bmRequestType=0x20, bRequest=0); HCI events arrive
 *   on the interrupt IN endpoint; ACL data uses bulk IN/OUT endpoints.
 * - Vol 4, Part E (HCI): command/event packet formats and opcodes.
 *
 * Clean-room implementation: derived from the public spec, not from any
 * third-party code.
 */
object HciProtocol {

    // USB transport: HCI commands are sent with a class-specific control transfer.
    const val USB_BT_REQ_TYPE_CLASS_INTERFACE_OUT = 0x20
    const val USB_BT_CONTROL_REQUEST = 0x00

    // USB-IF "Wireless Controller" class codes identifying a Bluetooth dongle.
    const val USB_CLASS_WIRELESS_CONTROLLER = 0xE0
    const val USB_SUBCLASS_RF = 0x01
    const val USB_PROTOCOL_BLUETOOTH = 0x01

    // H4 indicators are not present on the separate USB interrupt/bulk endpoints.
    const val HCI_EVENT_PACKET = 0x04
    const val HCI_ACL_PACKET = 0x02

    // HCI event codes we care about during init.
    const val EVT_COMMAND_COMPLETE = 0x0E
    const val EVT_COMMAND_STATUS = 0x0F

    /** HCI opcode = (OGF << 10) | OCF. */
    fun opcode(ogf: Int, ocf: Int): Int = (ogf shl 10) or ocf

    val OP_RESET = opcode(0x03, 0x0003)
    val OP_READ_LOCAL_VERSION = opcode(0x04, 0x0001)
    val OP_READ_LOCAL_FEATURES = opcode(0x04, 0x0003)
    val OP_READ_BD_ADDR = opcode(0x04, 0x0009)
    val OP_READ_BUFFER_SIZE = opcode(0x04, 0x0005)
    val OP_WRITE_SCAN_ENABLE = opcode(0x03, 0x001A)
    val OP_WRITE_LOCAL_NAME = opcode(0x03, 0x0013)
    val OP_WRITE_CLASS_OF_DEVICE = opcode(0x03, 0x0024)
    val OP_WRITE_INQUIRY_MODE = opcode(0x03, 0x0045)
    val OP_WRITE_INQUIRY_SCAN_TYPE = opcode(0x03, 0x0043)
    val OP_WRITE_PAGE_SCAN_TYPE = opcode(0x03, 0x0047)
    val OP_WRITE_PAGE_SCAN_ACTIVITY = opcode(0x03, 0x001C)
    val OP_WRITE_INQUIRY_SCAN_ACTIVITY = opcode(0x03, 0x001E)
    val OP_WRITE_DEFAULT_LINK_POLICY = opcode(0x02, 0x000F)
    val OP_READ_INQUIRY_RESPONSE_TX_POWER = opcode(0x03, 0x0058)
    val OP_WRITE_EXTENDED_INQUIRY_RESPONSE = opcode(0x03, 0x0052)

    // Write_Scan_Enable values.
    const val SCAN_DISABLED = 0x00
    const val SCAN_INQUIRY_AND_PAGE = 0x03

    /** USB HCI event header (2 bytes) plus up to 255 parameter bytes. */
    const val MAX_EVENT_PACKET_SIZE = 257

    /**
     * Encode an HCI command for the USB control transfer payload:
     * opcode (2 bytes LE) + total param length (1 byte) + params.
     * The 0x01 packet indicator is NOT included; the control transfer wraps it.
     */
    fun encodeCommand(opcode: Int, params: ByteArray = byteArrayOf()): ByteArray {
        require(params.size <= 255) { "HCI params too long: ${params.size}" }
        val out = ByteArray(3 + params.size)
        out[0] = (opcode and 0xFF).toByte()
        out[1] = ((opcode ushr 8) and 0xFF).toByte()
        out[2] = params.size.toByte()
        params.copyInto(out, 3)
        return out
    }

    /** One parsed HCI event packet. */
    data class HciEvent(val code: Int, val params: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is HciEvent) return false
            return code == other.code && params.contentEquals(other.params)
        }

        override fun hashCode(): Int = 31 * code + params.contentHashCode()
    }

    /** Parsed Command Complete (0x0E) event. */
    data class CommandComplete(
        val numHciCommandPackets: Int,
        val opcode: Int,
        val returnParams: ByteArray,
    )

    /** Parsed Command Status (0x0F) event. */
    data class CommandStatus(
        val status: Int,
        val numHciCommandPackets: Int,
        val opcode: Int,
    )

    /** Parse a USB HCI event: event_code | param_len | params, without an H4 indicator. */
    fun parseEvent(packet: ByteArray, length: Int = packet.size): HciEvent? {
        if (length < 2 || length > packet.size) return null
        val paramLen = packet[1].toInt() and 0xFF
        if (2 + paramLen != length) return null
        return HciEvent(
            code = packet[0].toInt() and 0xFF,
            params = packet.copyOfRange(2, 2 + paramLen),
        )
    }

    /** Parse Command Complete (0x0E) params. Null when malformed. */
    fun parseCommandComplete(event: HciEvent): CommandComplete? {
        if (event.code != EVT_COMMAND_COMPLETE || event.params.size < 3) return null
        val opcode = (event.params[1].toInt() and 0xFF) or
            ((event.params[2].toInt() and 0xFF) shl 8)
        return CommandComplete(
            numHciCommandPackets = event.params[0].toInt() and 0xFF,
            opcode = opcode,
            returnParams = event.params.copyOfRange(3, event.params.size),
        )
    }

    /** Parse Command Status (0x0F) params. Null when malformed. */
    fun parseCommandStatus(event: HciEvent): CommandStatus? {
        if (event.code != EVT_COMMAND_STATUS || event.params.size < 4) return null
        val opcode = (event.params[2].toInt() and 0xFF) or
            ((event.params[3].toInt() and 0xFF) shl 8)
        return CommandStatus(
            status = event.params[0].toInt() and 0xFF,
            numHciCommandPackets = event.params[1].toInt() and 0xFF,
            opcode = opcode,
        )
    }

    /**
     * Parse a Read_BD_ADDR return: status(1) + 6-byte address (LSB first on the wire).
     * Returns "XX:XX:XX:XX:XX:XX" (MSB first) or null when status != 0 / malformed.
     */
    fun parseBdAddr(returnParams: ByteArray): String? {
        if (returnParams.size < 7 || returnParams[0] != 0.toByte()) return null
        return (5 downTo 0).joinToString(":") { i ->
            "%02X".format(returnParams[1 + i])
        }
    }

    /** Parsed Read_Buffer_Size return. */
    data class BufferSize(
        val aclDataPacketLength: Int,
        val scoDataPacketLength: Int,
        val totalNumAclPackets: Int,
        val totalNumScoPackets: Int,
    )

    /** Parse Read_Buffer_Size return: status(1) + ACL len(2) + SCO len(1) + ACL pkts(2) + SCO pkts(2). */
    fun parseBufferSize(returnParams: ByteArray): BufferSize? {
        if (returnParams.size < 8 || returnParams[0] != 0.toByte()) return null
        fun u16(at: Int): Int =
            (returnParams[at].toInt() and 0xFF) or ((returnParams[at + 1].toInt() and 0xFF) shl 8)
        return BufferSize(
            aclDataPacketLength = u16(1),
            scoDataPacketLength = returnParams[3].toInt() and 0xFF,
            totalNumAclPackets = u16(4),
            totalNumScoPackets = u16(6),
        )
    }

    /** Encode a Write_Local_Name payload: up to 248 bytes, NUL-padded. */
    fun encodeLocalName(name: String): ByteArray {
        val raw = name.toByteArray(Charsets.UTF_8)
        val out = ByteArray(248)
        raw.copyInto(out, 0, 0, minOf(raw.size, out.size))
        return out
    }

    /** Encode Class of Device (3 bytes LE). Default handsfree car audio is 0x200408. */
    fun encodeWriteClassOfDevice(cod: Int): ByteArray = byteArrayOf(
        (cod and 0xFF).toByte(),
        ((cod ushr 8) and 0xFF).toByte(),
        ((cod ushr 16) and 0xFF).toByte(),
    )

    /** Encode Write_Extended_Inquiry_Response: FEC_Required(1) + 240 bytes EIR data. */
    fun encodeWriteExtendedInquiryResponse(eirData: ByteArray, fecRequired: Boolean = false): ByteArray {
        val out = ByteArray(241)
        out[0] = if (fecRequired) 1 else 0
        val copyLen = minOf(eirData.size, 240)
        eirData.copyInto(out, 1, 0, copyLen)
        return out
    }

    /** Encode Write_Inquiry_Scan_Type or Write_Page_Scan_Type: 1=interlaced, 0=standard. */
    fun encodeWriteScanType(interlaced: Boolean): ByteArray =
        byteArrayOf(if (interlaced) 1 else 0)

    /**
     * Encode Write_Inquiry_Scan_Activity or Write_Page_Scan_Activity:
     * interval (2 bytes LE) + window (2 bytes LE). Range: 0x0012 to 0x1000.
     */
    fun encodeWriteScanActivity(interval: Int, window: Int): ByteArray = byteArrayOf(
        (interval and 0xFF).toByte(),
        ((interval ushr 8) and 0xFF).toByte(),
        (window and 0xFF).toByte(),
        ((window ushr 8) and 0xFF).toByte(),
    )

    /** Encode Write_Inquiry_Mode: 0=standard, 1=with RSSI, 2=Extended Inquiry Result or RSSI. */
    fun encodeWriteInquiryMode(mode: Int): ByteArray =
        byteArrayOf((mode and 0xFF).toByte())

    /** Encode Write_Default_Link_Policy_Settings: 2 bytes LE. Default 0x0005 (role switch + sniff). */
    fun encodeWriteDefaultLinkPolicy(policy: Int): ByteArray = byteArrayOf(
        (policy and 0xFF).toByte(),
        ((policy ushr 8) and 0xFF).toByte(),
    )

    /** Encode Set_Event_Mask: 8 bytes LE. */
    fun encodeSetEventMask(mask: Long = -1L): ByteArray {
        val out = ByteArray(8)
        for (i in 0..7) {
            out[i] = ((mask ushr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    // ------------------------------------------------------------------
    // HCI ACL data packets (Phase 3).
    // ------------------------------------------------------------------

    /** Parsed HCI ACL data packet. */
    data class AclPacket(val handle: Int, val flags: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AclPacket) return false
            return handle == other.handle && flags == other.flags && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * (31 * handle + flags) + data.contentHashCode()
    }

    /** PB flag: first fragment of an L2CAP packet (start). */
    const val ACL_PB_FIRST_FLUSHABLE = 0x02

    /** Parse a USB HCI ACL packet: handle+flags (2 LE) | data_len (2 LE) | data. */
    fun parseAcl(packet: ByteArray, length: Int = packet.size): AclPacket? {
        if (length < 4 || length > packet.size) return null
        val handleFlags = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
        val dataLen = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        if (4 + dataLen > length) return null
        return AclPacket(
            handle = handleFlags and 0x0FFF,
            flags = (handleFlags ushr 12) and 0x0F,
            data = packet.copyOfRange(4, 4 + dataLen),
        )
    }

    /** Parse all consecutive USB HCI ACL packets in [packet] up to [length]. */
    fun parseAclPackets(packet: ByteArray, length: Int = packet.size): List<AclPacket> {
        if (length < 4 || length > packet.size) return emptyList()
        val list = ArrayList<AclPacket>(2)
        var offset = 0
        while (offset + 4 <= length) {
            val handleFlags = (packet[offset].toInt() and 0xFF) or ((packet[offset + 1].toInt() and 0xFF) shl 8)
            val dataLen = (packet[offset + 2].toInt() and 0xFF) or ((packet[offset + 3].toInt() and 0xFF) shl 8)
            if (offset + 4 + dataLen > length) break
            list.add(
                AclPacket(
                    handle = handleFlags and 0x0FFF,
                    flags = (handleFlags ushr 12) and 0x0F,
                    data = packet.copyOfRange(offset + 4, offset + 4 + dataLen),
                )
            )
            offset += 4 + dataLen
        }
        return list
    }

    /** Encode a USB HCI ACL packet without the UART H4 indicator. */
    fun encodeAcl(handle: Int, data: ByteArray): ByteArray {
        require(data.size <= 0xFFFF) { "HCI ACL payload too long: ${data.size}" }
        val out = ByteArray(4 + data.size)
        val handleFlags = (handle and 0x0FFF) or (ACL_PB_FIRST_FLUSHABLE shl 12)
        out[0] = (handleFlags and 0xFF).toByte()
        out[1] = ((handleFlags ushr 8) and 0xFF).toByte()
        out[2] = (data.size and 0xFF).toByte()
        out[3] = ((data.size ushr 8) and 0xFF).toByte()
        data.copyInto(out, 4)
        return out
    }

    // ------------------------------------------------------------------
    // HCI events needed for BR/EDR connection management (Phase 3/4).
    // ------------------------------------------------------------------
    const val EVT_CONNECTION_COMPLETE = 0x03
    const val EVT_CONNECTION_REQUEST = 0x04
    const val EVT_DISCONNECTION_COMPLETE = 0x05
    const val EVT_AUTHENTICATION_COMPLETE = 0x06
    const val EVT_ENCRYPTION_CHANGE = 0x08
    const val EVT_NUM_COMPLETED_PACKETS = 0x13
    const val EVT_PIN_CODE_REQUEST = 0x16
    const val EVT_LINK_KEY_REQUEST = 0x17
    const val EVT_LINK_KEY_NOTIFICATION = 0x18
    const val EVT_IO_CAPABILITY_REQUEST = 0x31
    const val EVT_IO_CAPABILITY_RESPONSE = 0x32
    const val EVT_USER_CONFIRMATION_REQUEST = 0x33
    const val EVT_SIMPLE_PAIRING_COMPLETE = 0x36

    val OP_CREATE_CONNECTION = opcode(0x01, 0x0005)
    val OP_DISCONNECT = opcode(0x01, 0x0006)
    val OP_ACCEPT_CONNECTION_REQUEST = opcode(0x01, 0x0009)
    val OP_LINK_KEY_REQUEST_REPLY = opcode(0x01, 0x000B)
    val OP_LINK_KEY_REQUEST_NEGATIVE_REPLY = opcode(0x01, 0x000C)
    val OP_PIN_CODE_REQUEST_REPLY = opcode(0x01, 0x000D)
    val OP_PIN_CODE_REQUEST_NEGATIVE_REPLY = opcode(0x01, 0x000E)
    val OP_AUTHENTICATION_REQUESTED = opcode(0x01, 0x0011)
    val OP_SET_CONNECTION_ENCRYPTION = opcode(0x01, 0x0013)
    val OP_IO_CAPABILITY_REQUEST_REPLY = opcode(0x01, 0x002B)
    val OP_USER_CONFIRMATION_REQUEST_REPLY = opcode(0x01, 0x002C)
    val OP_USER_CONFIRMATION_REQUEST_NEGATIVE_REPLY = opcode(0x01, 0x002D)
    val OP_WRITE_AUTHENTICATION_ENABLE = opcode(0x03, 0x0020)
    val OP_WRITE_SIMPLE_PAIRING_MODE = opcode(0x03, 0x0056)
    val OP_SET_EVENT_MASK = opcode(0x03, 0x0001)

    fun encodeAuthenticationRequested(handle: Int): ByteArray =
        byteArrayOf((handle and 0xFF).toByte(), ((handle ushr 8) and 0x0F).toByte())

    fun encodeSetConnectionEncryption(handle: Int, encrypt: Boolean): ByteArray =
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle ushr 8) and 0x0F).toByte(),
            if (encrypt) 0x01 else 0x00,
        )

    // Vendor firmware-download commands (OGF 0x3F). Used when HCI_Reset gets no
    // response from a dongle whose chip needs firmware uploaded first.
    // Without the vendor .hcd/.bin blob we can only enter download mode and
    // report; the blob itself is not bundled.
    val OP_BCM_DOWNLOAD_MINIDRIVER = opcode(0x3F, 0x002E)
    val OP_RTL_DOWNLOAD_COMMAND = opcode(0x3F, 0x0020)

    // IO capability values (for IO_Capability_Request_Reply).
    const val IO_CAP_DISPLAY_ONLY = 0x00
    const val IO_CAP_DISPLAY_YES_NO = 0x01
    const val IO_CAP_NO_INPUT_NO_OUTPUT = 0x03

    // ------------------------------------------------------------------
    // L2CAP (Bluetooth Core Spec Vol 3, Part A).
    // ------------------------------------------------------------------
    const val L2CAP_CID_SIGNALING = 0x0001
    const val L2CAP_PSM_SDP = 0x0001
    const val L2CAP_PSM_RFCOMM = 0x0003

    // L2CAP signaling command codes.
    const val L2CAP_CMD_CONNECTION_REQ = 0x02
    const val L2CAP_CMD_CONNECTION_RSP = 0x03
    const val L2CAP_CMD_CONFIG_REQ = 0x04
    const val L2CAP_CMD_CONFIG_RSP = 0x05
    const val L2CAP_CMD_DISCONNECTION_REQ = 0x06
    const val L2CAP_CMD_DISCONNECTION_RSP = 0x07

    // L2CAP Connection Response result codes.
    const val L2CAP_CONN_SUCCESS = 0x0000
    const val L2CAP_CONN_PENDING = 0x0001

    /**
     * Encode an L2CAP packet: length (2 LE) | CID (2 LE) | payload.
     * Length covers the payload only (not the 4-byte header).
     */
    fun encodeL2cap(cid: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(4 + payload.size)
        out[0] = (payload.size and 0xFF).toByte()
        out[1] = ((payload.size ushr 8) and 0xFF).toByte()
        out[2] = (cid and 0xFF).toByte()
        out[3] = ((cid ushr 8) and 0xFF).toByte()
        payload.copyInto(out, 4)
        return out
    }

    /** Parsed L2CAP packet. Null when malformed. */
    data class L2capPacket(val cid: Int, val payload: ByteArray)

    fun parseL2cap(data: ByteArray): L2capPacket? {
        if (data.size < 4) return null
        val length = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (4 + length > data.size) return null
        val cid = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
        return L2capPacket(cid = cid, payload = data.copyOfRange(4, 4 + length))
    }

    // ------------------------------------------------------------------
    // RFCOMM (Bluetooth Core Spec, RFCOMM with TS 07.10).
    // ------------------------------------------------------------------

    /** RFCOMM frame types. */
    const val RFCOMM_SABM = 0x2F
    const val RFCOMM_UA = 0x63
    const val RFCOMM_DM = 0x0F
    const val RFCOMM_DISC = 0x43
    const val RFCOMM_UIH = 0xEF
    const val RFCOMM_MCC = 0x33

    /** RFCOMM MCC message types. */
    const val RFCOMM_MCC_PN = 0x20
    const val RFCOMM_MCC_MSC = 0x38

    /** DLCI 0 is the RFCOMM session control channel. */
    const val RFCOMM_DLCI_CONTROL = 0

    /** Outbound DLC direction is 0 for the session initiator, 1 for its responder. */
    fun rfcommDlci(serverChannel: Int, initiator: Boolean): Int =
        serverChannel * 2 + if (initiator) 0 else 1

    // ------------------------------------------------------------------
    // SDP (Bluetooth Core Spec Vol 3, Part B).
    // ------------------------------------------------------------------
    const val SDP_PDU_SERVICE_SEARCH_ATTR_REQ = 0x06
    const val SDP_PDU_SERVICE_SEARCH_ATTR_RSP = 0x07

    /** iAP2 iPhone service class UUID (128-bit big-endian). */
    val IAP2_UUID_BYTES: ByteArray = uuidBigEndian(UsbBtIdentity.IAP2_IPHONE_UUID)

    // ------------------------------------------------------------------
    // BR/EDR connection management (Phase 5).
    // ------------------------------------------------------------------

    /**
     * Parse a BD_ADDR from the first 6 bytes (LSB first on the wire).
     * Returns "XX:XX:XX:XX:XX:XX" (MSB first) or null when too short.
     */
    fun parseBdAddrBytes(data: ByteArray, at: Int = 0): String? {
        if (at + 6 > data.size) return null
        return (5 downTo 0).joinToString(":") { i ->
            "%02X".format(data[at + i])
        }
    }

    /**
     * Encode a "XX:XX:XX:XX:XX:XX" address to 6 wire bytes (LSB first).
     * Returns null when malformed.
     */
    fun encodeBdAddr(address: String): ByteArray? {
        val parts = address.split(":")
        if (parts.size != 6) return null
        return runCatching {
            ByteArray(6) { i -> parts[5 - i].toInt(16).toByte() }
        }.getOrNull()
    }

    /**
     * Encode HCI_Create_Connection params: BD_ADDR(6) | packet_type(2)=0xCC18
     * | page_scan_repetition_mode(1)=1 | reserved(1)=0 | clock_offset(2)=0
     * | allow_role_switch(1)=1.
     */
    fun encodeCreateConnection(bdAddr: ByteArray): ByteArray {
        require(bdAddr.size == 6) { "BD_ADDR must be 6 bytes" }
        return bdAddr +
            byteArrayOf(0x18.toByte(), 0xCC.toByte(), 0x01, 0x00, 0x00, 0x00, 0x01)
    }

    /** Parsed Connection Complete (0x03) event. */
    data class ConnectionComplete(
        val status: Int,
        val handle: Int,
        val bdAddr: String?,
        val encryptionEnabled: Boolean = false,
    )

    /** Parse Connection Complete: status(1) | handle(2) | BD_ADDR(6) | link_type(1) | encryption(1). */
    fun parseConnectionComplete(event: HciEvent): ConnectionComplete? {
        if (event.code != EVT_CONNECTION_COMPLETE || event.params.size < 11) return null
        val handle = (event.params[1].toInt() and 0xFF) or
            (((event.params[2].toInt() and 0xFF) shl 8) and 0x0FFF)
        return ConnectionComplete(
            status = event.params[0].toInt() and 0xFF,
            handle = handle,
            bdAddr = parseBdAddrBytes(event.params, 3),
            encryptionEnabled = (event.params[10].toInt() and 0xFF) != 0,
        )
    }

    data class ConnectionRequest(
        val bdAddr: String,
        val classOfDevice: Int,
        val linkType: Int,
        val rawAddr: ByteArray,
    )

    /** Parse Connection Request: BD_ADDR(6) | Class_of_Device(3) | Link_Type(1). */
    fun parseConnectionRequest(event: HciEvent): ConnectionRequest? {
        if (event.code != EVT_CONNECTION_REQUEST || event.params.size < 10) return null
        val addr = parseBdAddrBytes(event.params, 0) ?: return null
        val cod = (event.params[6].toInt() and 0xFF) or
            ((event.params[7].toInt() and 0xFF) shl 8) or
            ((event.params[8].toInt() and 0xFF) shl 16)
        val linkType = event.params[9].toInt() and 0xFF
        return ConnectionRequest(
            bdAddr = addr,
            classOfDevice = cod,
            linkType = linkType,
            rawAddr = event.params.copyOfRange(0, 6),
        )
    }

    /** Encode Accept_Connection_Request: BD_ADDR(6 LE) | role(1). 0x01 = remain slave. */
    fun encodeAcceptConnection(addrBytes: ByteArray, role: Byte = 0x01): ByteArray {
        require(addrBytes.size == 6) { "BD_ADDR must be 6 bytes" }
        return addrBytes + byteArrayOf(role)
    }

    data class DisconnectionComplete(
        val status: Int,
        val handle: Int,
        val reason: Int,
    )

    /** Parse Disconnection Complete: status(1) | handle(2) | reason(1). */
    fun parseDisconnectionComplete(event: HciEvent): DisconnectionComplete? {
        if (event.code != EVT_DISCONNECTION_COMPLETE || event.params.size < 4) return null
        val handle = (event.params[1].toInt() and 0xFF) or
            (((event.params[2].toInt() and 0xFF) shl 8) and 0x0FFF)
        return DisconnectionComplete(
            status = event.params[0].toInt() and 0xFF,
            handle = handle,
            reason = event.params[3].toInt() and 0xFF,
        )
    }

    /** Encode HCI_Disconnect: handle(2) | reason(1)=0x13 (remote user terminated). */
    fun encodeDisconnect(handle: Int): ByteArray =
        byteArrayOf(
            (handle and 0xFF).toByte(),
            ((handle ushr 8) and 0xFF).toByte(),
            0x13,
        )

    // ------------------------------------------------------------------
    // HCI Inquiry ("car finds phone" discovery, src15). Core Spec Vol 4,
    // Part E, 7.1: the controller scans for nearby BR/EDR devices and
    // reports them through the result events parsed below. Which result
    // event a controller emits depends on Write_Inquiry_Mode (init sets
    // mode 2, so Extended Inquiry Result is expected; the RSSI and
    // standard forms cover controllers that ignore the mode write).
    // ------------------------------------------------------------------
    const val EVT_INQUIRY_COMPLETE = 0x01
    const val EVT_INQUIRY_RESULT = 0x02
    const val EVT_INQUIRY_RESULT_WITH_RSSI = 0x22
    const val EVT_EXTENDED_INQUIRY_RESULT = 0x2F

    val OP_INQUIRY = opcode(0x01, 0x0001)
    val OP_INQUIRY_CANCEL = opcode(0x01, 0x0002)

    /** General Inquiry Access Code: the LAP every discoverable device listens on. */
    const val LAP_GIAC = 0x9E8B33

    /**
     * Encode HCI_Inquiry params: LAP (3 bytes, LSB first) |
     * Inquiry_Length (1, in 1.28 s units) | Num_Responses (1, 0 = unlimited).
     */
    fun encodeInquiry(
        lap: Int = LAP_GIAC,
        inquiryLength: Int = 0x08,
        numResponses: Int = 0,
    ): ByteArray = byteArrayOf(
        (lap and 0xFF).toByte(),
        ((lap ushr 8) and 0xFF).toByte(),
        ((lap ushr 16) and 0xFF).toByte(),
        (inquiryLength and 0xFF).toByte(),
        (numResponses and 0xFF).toByte(),
    )

    /** One device found by an inquiry, merged from any of the result events. */
    data class InquiryDevice(
        val address: String,
        val name: String?,
        val rssi: Int?,
        val classOfDevice: Int,
    )

    private fun inquiryCod(params: ByteArray, at: Int): Int =
        (params[at].toInt() and 0xFF) or
            ((params[at + 1].toInt() and 0xFF) shl 8) or
            ((params[at + 2].toInt() and 0xFF) shl 16)

    /**
     * Shared parse for the three inquiry result events.
     *
     * Core Spec Vol 4, Part E, 6: the parameters are NOT per-response
     * records. After Num_Responses(1) the fields are grouped — all
     * BD_ADDRs (6 each), then all Page_Scan_Repetition_Modes (1), then
     * the Reserved group, then all Class_of_Devices (3), then all
     * Clock_Offsets (2), then (0x22/0x2F only) all RSSI bytes (1, signed),
     * then (0x2F only) all EIR blocks (240). A per-response record walk
     * coincides with this layout only when Num_Responses == 1, which is
     * why single-device results used to parse while real multi-device
     * results were garbage (src17 fix). The Reserved group also differs
     * per event: 2 bytes per response for 0x02, only 1 for 0x22/0x2F —
     * reading 0x22 with 2 shifted its CoD and RSSI one byte late even for
     * a single response, so no found device ever classified as a phone.
     *
     * A response is emitted only when every one of its field slices lies
     * inside the buffer; slice ends grow monotonically with the response
     * index, so the first incomplete response ends the parse and a
     * truncated tail drops just the incomplete responses.
     */
    private fun parseInquiryFields(
        event: HciEvent,
        expectedCode: Int,
        reservedBytes: Int,
        hasRssi: Boolean,
        eirSize: Int,
    ): List<InquiryDevice> {
        if (event.code != expectedCode || event.params.isEmpty()) return emptyList()
        val params = event.params
        val count = params[0].toInt() and 0xFF
        if (count == 0) return emptyList()
        val addrBase = 1
        val codBase = addrBase + count * 7 + reservedBytes * count
        val clockBase = codBase + count * 3
        val rssiBase = clockBase + count * 2
        val eirBase = rssiBase + count
        // Exclusive end of response i's slice of the LAST field group;
        // every earlier group's slice for response i ends no later.
        fun lastSliceEnd(i: Int): Int = when {
            eirSize > 0 -> eirBase + eirSize * (i + 1)
            hasRssi -> rssiBase + (i + 1)
            else -> clockBase + 2 * (i + 1)
        }
        val out = ArrayList<InquiryDevice>(count)
        for (i in 0 until count) {
            if (lastSliceEnd(i) > params.size) break
            val address = parseBdAddrBytes(params, addrBase + 6 * i) ?: continue
            out += InquiryDevice(
                address = address,
                name = if (eirSize > 0) {
                    val eirStart = eirBase + eirSize * i
                    parseEirLocalName(params.copyOfRange(eirStart, eirStart + eirSize))
                } else null,
                rssi = if (hasRssi) params[rssiBase + i].toInt() else null,
                classOfDevice = inquiryCod(params, codBase + 3 * i),
            )
        }
        return out
    }

    /**
     * Parse Inquiry Result (0x02): Num_Responses(1), then field groups
     * BD_ADDR(6) | Page_Scan_Repetition_Mode(1) | Reserved(2) |
     * Class_of_Device(3) | Clock_Offset(2), each repeated per response.
     */
    fun parseInquiryResult(event: HciEvent): List<InquiryDevice> =
        parseInquiryFields(event, EVT_INQUIRY_RESULT, reservedBytes = 2, hasRssi = false, eirSize = 0)

    /**
     * Parse Inquiry Result with RSSI (0x22): same field groups as the
     * standard result but Reserved is 1 byte per response, and an RSSI
     * group (1 signed byte per response) follows the Clock_Offsets.
     */
    fun parseInquiryResultWithRssi(event: HciEvent): List<InquiryDevice> =
        parseInquiryFields(
            event, EVT_INQUIRY_RESULT_WITH_RSSI, reservedBytes = 1, hasRssi = true, eirSize = 0,
        )

    /**
     * Parse Extended Inquiry Result (0x2F): the 0x22 groups (Reserved 1
     * byte per response), then a 240-byte EIR block per response. The
     * device name comes from the EIR Complete / Shortened Local Name
     * entries when present. One response already fills the 255-byte
     * parameter limit, so Num_Responses is 1 in any real event.
     */
    fun parseExtendedInquiryResult(event: HciEvent): List<InquiryDevice> =
        parseInquiryFields(
            event, EVT_EXTENDED_INQUIRY_RESULT, reservedBytes = 1, hasRssi = true, eirSize = 240,
        )

    /**
     * Extract the local name from EIR data: a sequence of
     * length(1) | type(1) | data(length-1) structures. Type 0x09 is the
     * Complete Local Name, 0x08 the Shortened Local Name; the complete
     * form wins. Returns null when no usable name is present.
     */
    fun parseEirLocalName(eirData: ByteArray): String? {
        var offset = 0
        var shortened: String? = null
        while (offset + 2 <= eirData.size) {
            val length = eirData[offset].toInt() and 0xFF
            if (length == 0 || offset + 1 + length > eirData.size) break
            val type = eirData[offset + 1].toInt() and 0xFF
            if (type == 0x09 || type == 0x08) {
                val name = String(eirData, offset + 2, length - 1, Charsets.UTF_8)
                    .trimEnd('\u0000').trim()
                if (name.isNotEmpty()) {
                    if (type == 0x09) return name
                    if (shortened == null) shortened = name
                }
            }
            offset += 1 + length
        }
        return shortened
    }

    /** Parse Inquiry Complete (0x01): Status(1). Null when malformed. */
    fun parseInquiryComplete(event: HciEvent): Int? {
        if (event.code != EVT_INQUIRY_COMPLETE || event.params.isEmpty()) return null
        return event.params[0].toInt() and 0xFF
    }

    /** Encode Link_Key_Request_Reply: BD_ADDR(6 LE) | Link_Key(16). */
    fun encodeLinkKeyReply(addrBytes: ByteArray, linkKey: ByteArray): ByteArray {
        require(addrBytes.size == 6) { "BD_ADDR must be 6 bytes" }
        require(linkKey.size == 16) { "Link Key must be 16 bytes" }
        val out = ByteArray(22)
        addrBytes.copyInto(out, 0)
        linkKey.copyInto(out, 6)
        return out
    }

    /** Encode Link_Key_Request_Negative_Reply: BD_ADDR(6 LE). */
    fun encodeLinkKeyNegativeReply(addrBytes: ByteArray): ByteArray {
        require(addrBytes.size == 6) { "BD_ADDR must be 6 bytes" }
        return addrBytes.copyOf()
    }

    /** Encode PIN_Code_Request_Negative_Reply: BD_ADDR(6 LE). */
    fun encodePinCodeNegativeReply(addrBytes: ByteArray): ByteArray {
        require(addrBytes.size == 6) { "BD_ADDR must be 6 bytes" }
        return addrBytes.copyOf()
    }
}
