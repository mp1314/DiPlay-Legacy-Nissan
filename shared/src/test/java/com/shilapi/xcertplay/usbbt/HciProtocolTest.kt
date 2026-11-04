package com.shilapi.xcertplay.usbbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HciProtocolTest {

    @Test
    fun encodeResetCommand() {
        // HCI_Reset = OGF 0x03, OCF 0x0003 -> opcode 0x0C03, no params.
        assertArrayEquals(
            byteArrayOf(0x03, 0x0C, 0x00),
            HciProtocol.encodeCommand(HciProtocol.OP_RESET),
        )
        assertEquals(0x0C03, HciProtocol.OP_RESET)
    }

    @Test
    fun encodeCommandWithParams() {
        // Write_Scan_Enable(0x03): opcode 0x0C1A + len 1 + param.
        assertArrayEquals(
            byteArrayOf(0x1A, 0x0C, 0x01, 0x03),
            HciProtocol.encodeCommand(HciProtocol.OP_WRITE_SCAN_ENABLE, byteArrayOf(0x03)),
        )
    }

    @Test
    fun parseCommandCompleteEvent() {
        // USB 0x0E | len=4 | numPkts=1 | opcode 0x0C03 LE | status=0
        val packet = byteArrayOf(0x0E, 0x04, 0x01, 0x03, 0x0C, 0x00)
        val event = HciProtocol.parseEvent(packet, packet.size)!!
        assertEquals(0x0E, event.code)
        val complete = HciProtocol.parseCommandComplete(event)!!
        assertEquals(1, complete.numHciCommandPackets)
        assertEquals(0x0C03, complete.opcode)
        assertArrayEquals(byteArrayOf(0x00), complete.returnParams)
    }

    @Test
    fun parseCommandStatusEvent() {
        // USB 0x0F | len=4 | status=0 | numPkts=1 | opcode 0x1009 LE
        val packet = byteArrayOf(0x0F, 0x04, 0x00, 0x01, 0x09, 0x10)
        val event = HciProtocol.parseEvent(packet, packet.size)!!
        val status = HciProtocol.parseCommandStatus(event)!!
        assertEquals(0, status.status)
        assertEquals(0x1009, status.opcode)
    }

    @Test
    fun parseMalformedEventsReturnNull() {
        assertNull(HciProtocol.parseEvent(byteArrayOf(0x0E), 1)) // truncated header
        assertNull(HciProtocol.parseEvent(byteArrayOf(0x02, 0x0E, 0x00), 3)) // malformed length
        // Declared length longer than the bytes we actually read.
        assertNull(HciProtocol.parseEvent(byteArrayOf(0x0E, 0x04, 0x01), 3))
        // Command Complete with fewer than 3 param bytes.
        val short = HciProtocol.HciEvent(0x0E, byteArrayOf(0x01, 0x03))
        assertNull(HciProtocol.parseCommandComplete(short))
        // Wrong event code.
        val wrong = HciProtocol.HciEvent(0x0D, byteArrayOf(0x01, 0x03, 0x0C, 0x00))
        assertNull(HciProtocol.parseCommandComplete(wrong))
    }

    @Test
    fun parseBdAddr() {
        // status=0 + 6 address bytes LSB-first: 11:22:33:44:55:66 on the wire as 66 55 44 33 22 11.
        val ret = byteArrayOf(0x00, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)
        assertEquals("11:22:33:44:55:66", HciProtocol.parseBdAddr(ret))
        assertNull(HciProtocol.parseBdAddr(byteArrayOf(0x01, 0x66, 0x55, 0x44, 0x33, 0x22, 0x11)))
        assertNull(HciProtocol.parseBdAddr(byteArrayOf(0x00, 0x11)))
    }

    @Test
    fun parseBufferSize() {
        // status=0, aclLen=1021 (0x03FD LE), scoLen=255, aclPkts=8, scoPkts=1
        val ret = byteArrayOf(0x00, 0xFD.toByte(), 0x03, 0xFF.toByte(), 0x08, 0x00, 0x01, 0x00)
        val bs = HciProtocol.parseBufferSize(ret)!!
        assertEquals(1021, bs.aclDataPacketLength)
        assertEquals(255, bs.scoDataPacketLength)
        assertEquals(8, bs.totalNumAclPackets)
        assertEquals(1, bs.totalNumScoPackets)
        assertNull(HciProtocol.parseBufferSize(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun encodeLocalNameIsPaddedTo248() {
        val out = HciProtocol.encodeLocalName("DiPlay")
        assertEquals(248, out.size)
        assertEquals("DiPlay", out.copyOfRange(0, 6).toString(Charsets.UTF_8))
        assertEquals(0.toByte(), out[247])
        // Over-long names are truncated, never overflow.
        val long = HciProtocol.encodeLocalName("x".repeat(300))
        assertEquals(248, long.size)
    }

    @Test
    fun aclEncodeParseRoundTrip() {
        val data = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val packet = HciProtocol.encodeAcl(handle = 0x0042, data = data)
        // USB handle+flags LE | len LE | data
        assertArrayEquals(byteArrayOf(0x42, 0x20, 0x04, 0x00, 0x01, 0x02, 0x03, 0x04), packet)
        val parsed = HciProtocol.parseAcl(packet)
        assertEquals(0x0042, parsed?.handle)
        assertArrayEquals(data, parsed?.data)
    }

    @Test
    fun aclParseRejectsTruncated() {
        assertNull(HciProtocol.parseAcl(byteArrayOf(0x02, 0x01)))
        // Declared length exceeds buffer.
        assertNull(
            HciProtocol.parseAcl(byteArrayOf(0x42, 0x20, 0x10, 0x00, 0x01, 0x02)),
        )
    }

    @Test
    fun aclParseAcceptsTrailingPadding() {
        val data = byteArrayOf(0x01, 0x02)
        val packet = HciProtocol.encodeAcl(handle = 0x0042, data = data) + byteArrayOf(0x00, 0x00, 0x00)
        val parsed = HciProtocol.parseAcl(packet)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals(0x0042, parsed?.handle)
        assertArrayEquals(data, parsed?.data)
    }

    @Test
    fun aclParseMultiplePackets() {
        val p1 = HciProtocol.encodeAcl(handle = 0x01, data = byteArrayOf(0x0A, 0x0B))
        val p2 = HciProtocol.encodeAcl(handle = 0x02, data = byteArrayOf(0x0C, 0x0D, 0x0E))
        val packets = HciProtocol.parseAclPackets(p1 + p2)
        assertEquals(2, packets.size)
        assertEquals(0x01, packets[0].handle)
        assertArrayEquals(byteArrayOf(0x0A, 0x0B), packets[0].data)
        assertEquals(0x02, packets[1].handle)
        assertArrayEquals(byteArrayOf(0x0C, 0x0D, 0x0E), packets[1].data)
    }

    @Test
    fun l2capEncodeParseRoundTrip() {
        val payload = byteArrayOf(0x0A, 0x0B, 0x0C)
        val frame = HciProtocol.encodeL2cap(cid = 0x0040, payload = payload)
        val parsed = HciProtocol.parseL2cap(frame)
        assertEquals(0x0040, parsed?.cid)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun bdAddrEncodeParseRoundTrip() {
        val addr = "A1:B2:C3:D4:E5:F6"
        val wire = HciProtocol.encodeBdAddr(addr)
        // LSB first on the wire.
        assertArrayEquals(
            byteArrayOf(0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte()),
            wire,
        )
        assertEquals(addr, wire?.let { HciProtocol.parseBdAddrBytes(it) })
    }

    @Test
    fun connectionCompleteParse() {
        // USB 0x03 | len=11 | status=0 | handle=0x0042 LE | BD_ADDR LSB-first | link_type | encrypt
        val packet = byteArrayOf(
            0x03, 0x0B, 0x00,
            0x42, 0x00,
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
            0x01, 0x00,
        )
        val event = HciProtocol.parseEvent(packet)
        val cc = event?.let { HciProtocol.parseConnectionComplete(it) }
        assertEquals(0, cc?.status)
        assertEquals(0x0042, cc?.handle)
        assertEquals("A1:B2:C3:D4:E5:F6", cc?.bdAddr)
    }

    @Test
    fun rfcommDlciEncoding() {
        // Session initiator opens the peer's channel with direction bit 0.
        assertEquals(2, HciProtocol.rfcommDlci(1, initiator = true))
        // Session responder uses direction bit 1 for an outbound DLC.
        assertEquals(5, HciProtocol.rfcommDlci(2, initiator = false))
        assertEquals(HciProtocol.RFCOMM_DLCI_CONTROL, 0)
    }

    // ------------------------------------------------------------------
    // Inquiry (src15): "car finds phone" discovery.
    // ------------------------------------------------------------------

    @Test
    fun encodeInquiryCommand() {
        // HCI_Inquiry = OGF 0x01, OCF 0x0001 -> opcode 0x0401.
        // Params: LAP GIAC LSB-first (33 8B 9E) | length | num_responses.
        assertEquals(0x0401, HciProtocol.OP_INQUIRY)
        assertEquals(0x0402, HciProtocol.OP_INQUIRY_CANCEL)
        assertArrayEquals(
            byteArrayOf(0x01, 0x04, 0x05, 0x33, 0x8B.toByte(), 0x9E.toByte(), 0x08, 0x00),
            HciProtocol.encodeCommand(
                HciProtocol.OP_INQUIRY,
                HciProtocol.encodeInquiry(inquiryLength = 0x08),
            ),
        )
    }

    // All inquiry vectors below are assembled byte-by-byte from the Core
    // Spec field-group layout (all BD_ADDRs, then all PSRMs, then the
    // Reserved group, then all CoDs, then all Clock_Offsets, then the
    // RSSI / EIR groups) — never from the parser's own assumptions. The
    // src15 vectors for 0x22 encoded a 2-byte Reserved field (copied from
    // the buggy implementation) and never asserted the CoD, which is why
    // the one-byte shift shipped unnoticed.

    @Test
    fun parseInquiryResultMultiResponse() {
        // 0x02, Num_Responses=2, Reserved group is 2 bytes per response.
        val params = byteArrayOf(
            0x02,
            // BD_ADDR group (LSB first): A, then B.
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            // Page_Scan_Repetition_Mode group: A, B.
            0x01, 0x02,
            // Reserved group (2 per response).
            0x00, 0x00, 0x00, 0x00,
            // Class_of_Device group: A = 0x5A020C (phone), B = 0x200404.
            0x0C, 0x02, 0x5A,
            0x04, 0x04, 0x20,
            // Clock_Offset group: A, B.
            0x34, 0x12,
            0x78, 0x56,
        )
        val devices = HciProtocol.parseInquiryResult(HciProtocol.HciEvent(0x02, params))
        assertEquals(2, devices.size)
        assertEquals("A1:B2:C3:D4:E5:F6", devices[0].address)
        assertEquals(0x5A020C, devices[0].classOfDevice)
        assertNull(devices[0].name)
        assertNull(devices[0].rssi)
        assertEquals("66:55:44:33:22:11", devices[1].address)
        assertEquals(0x200404, devices[1].classOfDevice)
    }

    @Test
    fun parseInquiryResultWithRssiEvent() {
        // 0x22, Num_Responses=1. The Reserved group is ONE byte here (it
        // is two in 0x02): PSRM | Reserved(1) | CoD | Clock | RSSI.
        // 0xC4 = -60 dBm. Regression test for the src15 shift: the old
        // parser read the CoD one byte late (0x345A02) on this vector.
        val params = byteArrayOf(
            0x01,
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
            0x01,
            0x00,
            0x0C, 0x02, 0x5A,
            0x34, 0x12,
            0xC4.toByte(),
        )
        val devices = HciProtocol.parseInquiryResultWithRssi(HciProtocol.HciEvent(0x22, params))
        assertEquals(1, devices.size)
        assertEquals("A1:B2:C3:D4:E5:F6", devices[0].address)
        assertEquals(0x5A020C, devices[0].classOfDevice)
        assertEquals(-60, devices[0].rssi)
        assertNull(devices[0].name)
    }

    @Test
    fun parseInquiryResultWithRssiMultiResponse() {
        // 0x22, Num_Responses=2, Reserved group is 1 byte per response,
        // RSSI group (one signed byte per response) comes last.
        val params = byteArrayOf(
            0x02,
            // BD_ADDR group: A, then B.
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            // Page_Scan_Repetition_Mode group.
            0x01, 0x02,
            // Reserved group (1 per response).
            0x00, 0x00,
            // Class_of_Device group: A = phone, B = 0x200404.
            0x0C, 0x02, 0x5A,
            0x04, 0x04, 0x20,
            // Clock_Offset group.
            0x34, 0x12,
            0x78, 0x56,
            // RSSI group: A = -60, B = -40.
            0xC4.toByte(), 0xD8.toByte(),
        )
        val devices = HciProtocol.parseInquiryResultWithRssi(HciProtocol.HciEvent(0x22, params))
        assertEquals(2, devices.size)
        assertEquals("A1:B2:C3:D4:E5:F6", devices[0].address)
        assertEquals(0x5A020C, devices[0].classOfDevice)
        assertEquals(-60, devices[0].rssi)
        assertEquals("66:55:44:33:22:11", devices[1].address)
        assertEquals(0x200404, devices[1].classOfDevice)
        assertEquals(-40, devices[1].rssi)
    }

    @Test
    fun parseExtendedInquiryResultEvent() {
        // 0x2F, Num_Responses=1, assembled field-group by field-group:
        // BD_ADDR | PSRM | Reserved(1) | CoD | Clock_Offset | RSSI | EIR(240).
        // Num_Responses cannot exceed 1 in a real event: one response
        // already fills the 255-byte HCI parameter limit.
        val eir = ByteArray(240)
        val name = "Pixel 9".toByteArray(Charsets.UTF_8)
        eir[0] = (name.size + 1).toByte() // structure length includes the type byte
        eir[1] = 0x09 // Complete Local Name
        name.copyInto(eir, 2)
        val params = ByteArray(1 + 254)
        params[0] = 0x01
        byteArrayOf(
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
        ).copyInto(params, 1)
        params[7] = 0x01 // page scan repetition mode
        params[8] = 0x00 // reserved (1 byte, as in 0x22)
        params[9] = 0x0C; params[10] = 0x02; params[11] = 0x5A // CoD 0x5A020C
        params[12] = 0x34; params[13] = 0x12 // clock offset
        params[14] = 0xBE.toByte() // RSSI = -66
        eir.copyInto(params, 15)
        val devices = HciProtocol.parseExtendedInquiryResult(HciProtocol.HciEvent(0x2F, params))
        assertEquals(1, devices.size)
        assertEquals("A1:B2:C3:D4:E5:F6", devices[0].address)
        assertEquals("Pixel 9", devices[0].name)
        assertEquals(-66, devices[0].rssi)
        assertEquals(0x5A020C, devices[0].classOfDevice)
    }

    @Test
    fun parseEirLocalNamePreference() {
        // Shortened name (0x08) is used only when no complete name (0x09) exists.
        val shortenedOnly = byteArrayOf(0x06, 0x08) + "Pixel".toByteArray(Charsets.UTF_8)
        assertEquals("Pixel", HciProtocol.parseEirLocalName(shortenedOnly))
        val both = byteArrayOf(0x06, 0x08) + "Pixel".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0x08, 0x09) + "Pixel 9".toByteArray(Charsets.UTF_8)
        assertEquals("Pixel 9", HciProtocol.parseEirLocalName(both))
        assertNull(HciProtocol.parseEirLocalName(byteArrayOf(0x02, 0x01, 0x06)))
        assertNull(HciProtocol.parseEirLocalName(ByteArray(0)))
    }

    @Test
    fun parseInquiryCompleteAndTruncatedResults() {
        assertEquals(
            0,
            HciProtocol.parseInquiryComplete(HciProtocol.HciEvent(0x01, byteArrayOf(0x00))),
        )
        assertNull(HciProtocol.parseInquiryComplete(HciProtocol.HciEvent(0x01, ByteArray(0))))
        assertNull(HciProtocol.parseInquiryComplete(HciProtocol.HciEvent(0x02, byteArrayOf(0x00))))
        // The two-response 0x02 vector from parseInquiryResultMultiResponse
        // with the final byte (second response's Clock_Offset high byte)
        // missing: only the fully present first response survives.
        val full = byteArrayOf(
            0x02,
            0xF6.toByte(), 0xE5.toByte(), 0xD4.toByte(), 0xC3.toByte(), 0xB2.toByte(), 0xA1.toByte(),
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
            0x01, 0x02,
            0x00, 0x00, 0x00, 0x00,
            0x0C, 0x02, 0x5A,
            0x04, 0x04, 0x20,
            0x34, 0x12,
            0x78, 0x56,
        )
        val truncated = full.copyOf(full.size - 1)
        val kept = HciProtocol.parseInquiryResult(HciProtocol.HciEvent(0x02, truncated))
        assertEquals(1, kept.size)
        assertEquals("A1:B2:C3:D4:E5:F6", kept[0].address)
        assertEquals(0x5A020C, kept[0].classOfDevice)
        // Only the BD_ADDR group present (declared 2 responses): no field
        // group after it exists, so nothing is complete enough to report.
        val addrOnly = full.copyOf(1 + 12)
        assertEquals(0, HciProtocol.parseInquiryResult(HciProtocol.HciEvent(0x02, addrOnly)).size)
        // Wrong event codes parse to nothing.
        assertEquals(0, HciProtocol.parseInquiryResult(HciProtocol.HciEvent(0x22, full)).size)
        assertEquals(0, HciProtocol.parseExtendedInquiryResult(HciProtocol.HciEvent(0x02, full)).size)
    }
}
