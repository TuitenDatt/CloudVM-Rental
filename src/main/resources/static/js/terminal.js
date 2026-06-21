/**
 * terminal.js v4 — Xterm.js + AWS SSM WebSocket Integration
 *
 * Chức năng:
 * 1. Khởi tạo Xterm.js terminal với theme đẹp
 * 2. Kết nối WebSocket đến AWS SSM Session Manager (streamUrl)
 * 3. Xử lý AWS SSM binary message protocol
 * 4. Bidirectional I/O: Xterm input → WebSocket → AWS, AWS → WebSocket → Xterm
 *
 * AWS SSM WebSocket Protocol:
 * - Message đầu tiên phải là TokenMessage để authenticate
 * - Sau đó là OutputStreamData (binary) để nhận output
 * - InputStreamData (binary) để gửi input
 *
 * Tham khảo: aws/amazon-ssm-agent protocol (open source)
 */

'use strict';

console.log('[SSM] terminal.js v4 loaded — ACK fix + deduplication + correct Flags');

// ================================================================
// XTERM CONFIG
// ================================================================
const XTERM_OPTIONS = {
    theme: {
        background:   '#0a0d14',
        foreground:   '#e2e8f0',
        cursor:       '#60a5fa',
        cursorAccent: '#0a0d14',
        black:        '#1e293b',
        brightBlack:  '#475569',
        red:          '#f87171',
        brightRed:    '#fca5a5',
        green:        '#4ade80',
        brightGreen:  '#86efac',
        yellow:       '#facc15',
        brightYellow: '#fde047',
        blue:         '#60a5fa',
        brightBlue:   '#93c5fd',
        magenta:      '#c084fc',
        brightMagenta:'#d8b4fe',
        cyan:         '#22d3ee',
        brightCyan:   '#67e8f9',
        white:        '#cbd5e1',
        brightWhite:  '#f1f5f9',
    },
    fontFamily: "'JetBrains Mono', 'Fira Code', 'Courier New', monospace",
    fontSize: 14,
    lineHeight: 1.4,
    cursorStyle: 'bar',
    cursorBlink: true,
    scrollback: 5000,
    allowTransparency: true,
    macOptionIsMeta: true,
};

// ================================================================
// AWS SSM PROTOCOL CONSTANTS
// ================================================================

// Message type identifiers
const SSM_MSG_TYPE = {
    INPUT_STREAM_DATA:  'input_stream_data',
    OUTPUT_STREAM_DATA: 'output_stream_data',
    ACKNOWLEDGE:        'acknowledge',
    START_PUBLICATION:  'start_publication',
    PAUSE_PUBLICATION:  'pause_publication',
    CHANNEL_CLOSED:     'channel_closed',
};

// Header size trong SSM binary protocol
const SSM_HEADER_SIZE = 120; // bytes

/**
 * Khởi tạo Xterm.js terminal và kết nối WebSocket SSM.
 *
 * @param {string} streamUrl    WebSocket URL từ AWS SSM StartSession response
 * @param {string} tokenValue   Token để authenticate kết nối
 * @param {string} sessionId    SSM Session ID (để display và cleanup)
 */
function initTerminal(streamUrl, tokenValue, sessionId) {
    // Dọn dẹp terminal cũ nếu có
    if (window.activeTerminal) {
        window.activeTerminal.dispose();
    }
    if (window.activeWs) {
        window.activeWs.close();
    }

    const container = document.getElementById('terminal-container');
    container.innerHTML = ''; // Clear container

    // Khởi tạo Xterm.js
    const term = new Terminal(XTERM_OPTIONS);
    const fitAddon    = new FitAddon.FitAddon();
    const linksAddon  = new WebLinksAddon.WebLinksAddon();

    term.loadAddon(fitAddon);
    term.loadAddon(linksAddon);
    term.open(container);
    fitAddon.fit();
    term.focus(); // Đảm bảo nhận keyboard input ngay khi mở

    window.activeTerminal = term;

    // Tự động resize khi cửa sổ thay đổi kích thước
    const resizeObserver = new ResizeObserver(() => {
        try { fitAddon.fit(); } catch (_) {}
    });
    resizeObserver.observe(container);

    // Hiển thị message chào
    term.writeln('\x1b[36m╔════════════════════════════════════════════╗\x1b[0m');
    term.writeln('\x1b[36m║\x1b[0m    \x1b[1mCloudVM — SSM Web Terminal\x1b[0m              \x1b[36m║\x1b[0m');
    term.writeln('\x1b[36m║\x1b[0m    Session: \x1b[33m' + sessionId + '\x1b[0m  \x1b[36m║\x1b[0m');
    term.writeln('\x1b[36m╚════════════════════════════════════════════╝\x1b[0m');
    term.writeln('');
    term.writeln('\x1b[90mĐang kết nối tới máy chủ...\x1b[0m');

    // Kết nối WebSocket
    connectSsmWebSocket(term, streamUrl, tokenValue, sessionId);
}

/**
 * Kết nối WebSocket đến AWS SSM và thiết lập I/O bidirectional.
 *
 * @param {Terminal} term       Instance Xterm.js
 * @param {string} streamUrl    wss:// URL từ AWS
 * @param {string} tokenValue   Authentication token
 * @param {string} sessionId    Session ID
 */
function connectSsmWebSocket(term, streamUrl, tokenValue, sessionId) {
    const ws = new WebSocket(streamUrl);
    ws.binaryType = 'arraybuffer';
    ws._receivedSeqs = new Set(); // Dedup: mỗi seq# chỉ hiển thị 1 lần dù SSM retransmit
    window.activeWs = ws;

    let isAuthenticated = false;
    let clientSequenceNumber = 0;

    // ---- WebSocket Events ----

    ws.onopen = function() {
        console.log('[SSM] WebSocket connected, sending token...');

        // Bước 1: Gửi token authentication message dạng JSON String
        const tokenMsg = buildTokenMessage(tokenValue);
        ws.send(tokenMsg);

        // Bước 2: Gửi kích thước terminal để SSM khởi tạo PTY đúng kích thước
        setTimeout(function() {
            if (ws.readyState !== WebSocket.OPEN) return;
            const resizePayload = JSON.stringify({ cols: term.cols, rows: term.rows });
            // PayloadType=2 (TerminalResize), Flags=Data|Syn (0x05)
            const resizeMsg = buildSsmMessage(SSM_MSG_TYPE.INPUT_STREAM_DATA, resizePayload, clientSequenceNumber, 2, 5n);
            ws.send(resizeMsg);
            clientSequenceNumber++;
            console.log('[SSM] Initial resize sent:', term.cols, 'x', term.rows);
        }, 300);
    };

    ws.onmessage = function(event) {
        try {
            const data = event.data;

            // Data có thể là ArrayBuffer (binary) hoặc string (JSON)
            if (data instanceof ArrayBuffer) {
                handleBinaryMessage(term, ws, data);
            } else if (typeof data === 'string') {
                handleTextMessage(term, ws, data, tokenValue);
            }

        } catch (err) {
            console.error('[SSM] Error handling message:', err);
        }
    };

    ws.onclose = function(event) {
        // Nếu đây là WebSocket cũ (bị thay thế khi mở terminal mới), bỏ qua
        // Tránh race condition: onclose của WS cũ fire sau khi session mới đã mở
        if (window.activeWs !== ws) return;

        console.log('[SSM] WebSocket closed:', event.code, event.reason);
        term.writeln('');
        term.writeln('\x1b[33m⚠ Kết nối đã bị đóng. Code: ' + event.code + '\x1b[0m');
        term.writeln('\x1b[90mĐóng tab terminal để quay lại dashboard.\x1b[0m');

        updateTerminalStatusBadge('disconnected');
        document.getElementById('terminal-overlay').classList.add('hidden');
    };

    ws.onerror = function(error) {
        console.error('[SSM] WebSocket error:', error);
        term.writeln('\x1b[31m✗ Lỗi kết nối WebSocket.\x1b[0m');
        updateTerminalStatusBadge('error');
        document.getElementById('terminal-overlay').classList.add('hidden');
    };

    // ---- Xterm Input → WebSocket ----
    term.onData(function(inputData) {
        if (ws.readyState !== WebSocket.OPEN) return;

        const inputMsg = buildInputStreamMessage(inputData, clientSequenceNumber);
        ws.send(inputMsg);
        clientSequenceNumber++;
    });

    // ---- Xterm Resize → WebSocket ----
    term.onResize(function(size) {
        if (ws.readyState !== WebSocket.OPEN) return;
        console.log('[SSM] Terminal resized:', size.cols, 'x', size.rows);
        const resizePayload = JSON.stringify({ cols: size.cols, rows: size.rows });
        // PayloadType=2 (TerminalResize), Flags=Data|Syn (0x05)
        const resizeMsg = buildSsmMessage(SSM_MSG_TYPE.INPUT_STREAM_DATA, resizePayload, clientSequenceNumber, 2, 5n);
        ws.send(resizeMsg);
        clientSequenceNumber++;
    });
}

/**
 * Xử lý binary message từ AWS SSM WebSocket.
 * Parse header để xác định message type, extract payload.
 *
 * AWS SSM Binary Message Format (116-byte header):
 * Offset  Size  Field
 * 0       4     HeaderLength
 * 4       4     MessageType (padded)
 * 8       4     SchemaVersion
 * 12      8     CreatedDate (unix ms)
 * 20      8     SequenceNumber
 * 28      8     Flags
 * 36      16    MessageId (UUID)
 * 52      32    Payload Digest (SHA256)
 * 84      4     PayloadType
 * 88      4     PayloadLength
 * 92      24    Reserved
 * 116     N     Payload
 *
 * @param {Terminal}     term            Xterm instance
 * @param {WebSocket}    ws              WebSocket connection
 * @param {ArrayBuffer}  data            Raw binary data
 */
function handleBinaryMessage(term, ws, data) {
    try {
        const buffer = new Uint8Array(data);

        if (buffer.length < SSM_HEADER_SIZE) {
            console.warn('[SSM] Message quá ngắn:', buffer.length);
            return;
        }

        // Đọc MessageType từ bytes 4-36 (null-padded or space-padded string)
        const msgTypeBytes = buffer.slice(4, 36);
        const msgType = new TextDecoder().decode(msgTypeBytes).replace(/\0/g, '').trim();

        const view = new DataView(data);
        // Đọc sequence number của server từ bytes 48-56
        const serverSeqNum = view.getBigInt64(48, false);
        // Đọc MessageId từ bytes 64-80 (16 bytes UUID) — cần cho ACK đúng format
        const messageId = bytesToUuid(buffer.slice(64, 80));

        // Đọc payload length từ bytes 116-120
        const payloadLength = view.getUint32(116, false); // big-endian

        if (msgType === SSM_MSG_TYPE.OUTPUT_STREAM_DATA && payloadLength > 0) {
            const seqNumInt = Number(serverSeqNum);

            // Luôn gửi ACK trước — kể cả retransmission
            sendAcknowledge(ws, serverSeqNum, messageId);

            // Dedup: chỉ hiển thị nếu seq này chưa từng nhận
            if (ws._receivedSeqs && ws._receivedSeqs.has(seqNumInt)) {
                console.log('[SSM] Retransmit seq:', seqNumInt, '— ACK sent, display skipped');
                return;
            }
            if (ws._receivedSeqs) ws._receivedSeqs.add(seqNumInt);

            // Extract payload và hiển thị lên terminal
            const payload = buffer.slice(SSM_HEADER_SIZE, SSM_HEADER_SIZE + payloadLength);
            const text = new TextDecoder('utf-8').decode(payload);
            term.write(text);

            // Ẩn overlay sau khi nhận output đầu tiên
            const overlay = document.getElementById('terminal-overlay');
            if (overlay && !overlay.classList.contains('hidden')) {
                overlay.classList.add('hidden');
                updateTerminalStatusBadge('connected');
            }
        }

        if (msgType === SSM_MSG_TYPE.CHANNEL_CLOSED) {
            term.writeln('\r\n\x1b[33m[Channel closed by server]\x1b[0m');
            ws.close();
        }

    } catch (err) {
        console.error('[SSM] Parse binary error:', err);
    }
}

/**
 * Xử lý text (JSON) message từ SSM.
 * Một số message được gửi dạng JSON string thay vì binary.
 */
function handleTextMessage(term, ws, data, tokenValue) {
    try {
        const msg = JSON.parse(data);
        console.log('[SSM] Text message:', msg);

        if (msg.MessageType === 'channel_closed') {
            term.writeln('\r\n\x1b[33m[Channel đã bị đóng]\x1b[0m');
            ws.close();
        }
    } catch (err) {
        // Không phải JSON, có thể là raw text
        term.write(data);
    }
}

// ================================================================
// SSM MESSAGE BUILDERS
// ================================================================

/**
 * Chuyển 16 bytes (UUID bytes từ header SSM) thành chuỗi UUID dạng:
 * xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
 * Cần thiết để build ACK message đúng format AWS SSM.
 *
 * @param {Uint8Array} bytes  16 bytes từ offset 64-80 của header
 * @returns {string}
 */
function bytesToUuid(bytes) {
    const hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20,32)}`;
}

function generateUUIDv4() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
        var r = Math.random() * 16 | 0, v = c === 'x' ? r : (r & 0x3 | 0x8);
        return v.toString(16);
    });
}

/**
 * Tạo Token Authentication message theo SSM protocol.
 * AWS yêu cầu token message phải là JSON String (không phải binary)
 * RequestId phải là UUID v4 hợp lệ.
 *
 * @param {string} tokenValue  Token từ StartSession response
 * @returns {string}
 */
function buildTokenMessage(tokenValue) {
    const msg = {
        MessageSchemaVersion: "1.0",
        RequestId: generateUUIDv4(),
        TokenValue: tokenValue,
        Client: "AWS-Systems-Manager-Session-Manager"
    };
    console.log('[SSM] Token message built with RequestId:', msg.RequestId);
    return JSON.stringify(msg);
}

/**
 * Tạo InputStreamData message để gửi keyboard input vào terminal.
 *
 * @param {string} inputText     Text từ Xterm keyboard input
 * @param {number} seqNum        Sequence number
 * @returns {ArrayBuffer}
 */
function buildInputStreamMessage(inputText, seqNum) {
    // PayloadType=1 (stdin), Flags=Data|Syn (0x05)
    return buildSsmMessage(SSM_MSG_TYPE.INPUT_STREAM_DATA, inputText, seqNum, 1, 5n);
}

/**
 * Gửi Acknowledge message sau khi nhận output từ server.
 * Cần thiết để SSM tiếp tục gửi data (flow control).
 *
 * @param {WebSocket} ws    WebSocket connection
 * @param {number}    seqNum  Sequence number cần ack
 */
/**
 * Gửi Acknowledge message theo đúng format AWS SSM protocol.
 * Thiếu AcknowledgedMessageId hoặc AcknowledgedMessageType → SSM không nhận ACK
 * → retransmit liên tục → terminal lặp lại output.
 *
 * @param {WebSocket} ws
 * @param {BigInt}    seqNum      Sequence number của message cần ACK
 * @param {string}    messageId   UUID của message cần ACK (bytes 64-80 của header)
 */
function sendAcknowledge(ws, seqNum, messageId) {
    if (ws.readyState !== WebSocket.OPEN) return;
    const seqNumInt = Number(seqNum);
    const ackPayload = JSON.stringify({
        AcknowledgedMessageType:           SSM_MSG_TYPE.OUTPUT_STREAM_DATA,
        AcknowledgedMessageId:             messageId,
        AcknowledgedMessageSequenceNumber: seqNumInt,
        IsBufferFull:                      false
    });
    // Flags = Syn (0x01) — theo SSM protocol spec cho ACK messages
    const ackMsg = buildSsmMessage(SSM_MSG_TYPE.ACKNOWLEDGE, ackPayload, seqNumInt, 0, 1n);
    ws.send(ackMsg);
    console.log('[SSM] ACK sent for seq:', seqNumInt, 'msgId:', messageId.slice(0, 8) + '...');
}

/**
 * Builder tổng quát cho SSM binary message.
 * Tạo 116-byte header + payload.
 *
 * @param {string} messageType    Loại message (SSM_MSG_TYPE)
 * @param {string} payloadStr     Payload dạng string (sẽ UTF-8 encode)
 * @param {number} sequenceNumber  Sequence number
 * @returns {ArrayBuffer}
 */
/**
 * Builder tổng quát cho SSM binary message.
 * Tạo 120-byte header + payload.
 *
 * @param {string} messageType     Loại message (SSM_MSG_TYPE)
 * @param {string} payloadStr      Payload dạng string (sẽ UTF-8 encode)
 * @param {number} sequenceNumber  Sequence number
 * @param {number} [payloadType=0] PayloadType: 0=default, 1=stdin/stdout, 2=TerminalResize
 * @param {bigint} [flags=0n]      Flags: 0=none, 1n=Syn, 4n=Data, 5n=Data|Syn
 * @returns {ArrayBuffer}
 */
function buildSsmMessage(messageType, payloadStr, sequenceNumber, payloadType = 0, flags = 0n) {
    const payloadBytes = new TextEncoder().encode(payloadStr);
    const totalSize    = SSM_HEADER_SIZE + payloadBytes.length;
    const buffer       = new ArrayBuffer(totalSize);
    const view         = new DataView(buffer);
    const byteArray    = new Uint8Array(buffer);

    // HeaderLength (4 bytes, big-endian) — header cố định 116 bytes (không tính PayloadLength)
    view.setUint32(0, 116, false);

    // MessageType (32 bytes, space-padded)
    const paddedMessageType = messageType.padEnd(32, ' ');
    const msgTypeBytes = new TextEncoder().encode(paddedMessageType);
    byteArray.set(msgTypeBytes, 4);

    // SchemaVersion (4 bytes)
    view.setUint32(36, 1, false);

    // CreatedDate (8 bytes, current time ms)
    const now = BigInt(Date.now());
    view.setBigUint64(40, now, false);

    // SequenceNumber (8 bytes)
    view.setBigUint64(48, BigInt(sequenceNumber), false);

    // Flags (8 bytes) — Syn=0x01, Data=0x04, Data|Syn=0x05
    view.setBigUint64(56, flags, false);

    // MessageId (16 bytes) — random UUID bytes
    const uuid = crypto.getRandomValues(new Uint8Array(16));
    byteArray.set(uuid, 64);

    // PayloadDigest (32 bytes) — zeros (not verified by SSM in this context)
    // offset 80, 32 bytes, already zeroed by ArrayBuffer

    // PayloadType (4 bytes) — caller specifies: 0=default, 1=stdin/stdout, 2=TerminalResize
    view.setUint32(112, payloadType, false);

    // PayloadLength (4 bytes)
    view.setUint32(116, payloadBytes.length, false);

    // Payload (starts at offset 120 = SSM_HEADER_SIZE)
    byteArray.set(payloadBytes, SSM_HEADER_SIZE);

    return buffer;
}

// ================================================================
// UI HELPERS
// ================================================================

/**
 * Cập nhật status badge trong terminal header.
 * @param {'connected'|'disconnected'|'error'} status
 */
function updateTerminalStatusBadge(status) {
    const badge = document.getElementById('terminal-status-badge');
    if (!badge) return;

    const config = {
        connected:    { class: 'status-running',    text: 'Đã kết nối' },
        disconnected: { class: 'status-stopped',    text: 'Mất kết nối' },
        error:        { class: 'status-stopped',    text: 'Lỗi kết nối' },
    };

    const cfg = config[status] || config.disconnected;
    badge.className = `status-badge ${cfg.class}`;
    badge.textContent = cfg.text;
}
