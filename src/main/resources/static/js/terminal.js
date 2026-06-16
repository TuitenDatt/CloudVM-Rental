/**
 * terminal.js — Xterm.js + AWS SSM WebSocket Integration
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
const SSM_HEADER_SIZE = 116; // bytes

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
    window.activeWs = ws;

    let isAuthenticated = false;
    let sequenceNumber  = 0;

    // ---- WebSocket Events ----

    ws.onopen = function() {
        console.log('[SSM] WebSocket connected, sending token...');

        // Bước 1: Gửi token authentication message dạng JSON String
        const tokenMsg = buildTokenMessage(tokenValue);
        ws.send(tokenMsg);
    };

    ws.onmessage = function(event) {
        try {
            const data = event.data;

            // Data có thể là ArrayBuffer (binary) hoặc string (JSON)
            if (data instanceof ArrayBuffer) {
                handleBinaryMessage(term, ws, data, sequenceNumber);
                sequenceNumber++;
            } else if (typeof data === 'string') {
                handleTextMessage(term, ws, data, tokenValue);
            }

        } catch (err) {
            console.error('[SSM] Error handling message:', err);
        }
    };

    ws.onclose = function(event) {
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

        const inputMsg = buildInputStreamMessage(inputData, sequenceNumber);
        ws.send(inputMsg);
        sequenceNumber++;
    });

    // ---- Xterm Resize → WebSocket ----
    term.onResize(function(size) {
        console.log('[SSM] Terminal resized:', size.cols, 'x', size.rows);
        // Gửi resize event nếu SSM document hỗ trợ
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
 * @param {number}       seqNum          Current sequence number
 */
function handleBinaryMessage(term, ws, data, seqNum) {
    try {
        const buffer = new Uint8Array(data);

        if (buffer.length < SSM_HEADER_SIZE) {
            console.warn('[SSM] Message quá ngắn:', buffer.length);
            return;
        }

        // Đọc MessageType từ bytes 4-36 (null-padded string)
        const msgTypeBytes = buffer.slice(4, 36);
        const msgType = new TextDecoder().decode(msgTypeBytes).replace(/\0/g, '').trim();

        // Đọc payload length từ bytes 88-92
        const view = new DataView(data);
        const payloadLength = view.getUint32(88, false); // big-endian

        if (msgType === SSM_MSG_TYPE.OUTPUT_STREAM_DATA && payloadLength > 0) {
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

            // Gửi acknowledge
            sendAcknowledge(ws, seqNum);
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
    return buildSsmMessage(SSM_MSG_TYPE.INPUT_STREAM_DATA, inputText, seqNum);
}

/**
 * Gửi Acknowledge message sau khi nhận output từ server.
 * Cần thiết để SSM tiếp tục gửi data (flow control).
 *
 * @param {WebSocket} ws    WebSocket connection
 * @param {number}    seqNum  Sequence number cần ack
 */
function sendAcknowledge(ws, seqNum) {
    if (ws.readyState !== WebSocket.OPEN) return;
    const ackPayload = JSON.stringify({ SequenceNumber: seqNum });
    const ackMsg = buildSsmMessage(SSM_MSG_TYPE.ACKNOWLEDGE, ackPayload, seqNum);
    ws.send(ackMsg);
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
function buildSsmMessage(messageType, payloadStr, sequenceNumber) {
    const payloadBytes = new TextEncoder().encode(payloadStr);
    const totalSize    = SSM_HEADER_SIZE + payloadBytes.length;
    const buffer       = new ArrayBuffer(totalSize);
    const view         = new DataView(buffer);
    const byteArray    = new Uint8Array(buffer);

    // HeaderLength (4 bytes, big-endian)
    view.setUint32(0, SSM_HEADER_SIZE, false);

    // MessageType (32 bytes, padded với null)
    const msgTypeBytes = new TextEncoder().encode(messageType);
    byteArray.set(msgTypeBytes, 4);

    // SchemaVersion (4 bytes)
    view.setUint32(36, 1, false);

    // CreatedDate (8 bytes, current time ms)
    const now = BigInt(Date.now());
    view.setBigUint64(40, now, false);

    // SequenceNumber (8 bytes)
    view.setBigUint64(48, BigInt(sequenceNumber), false);

    // Flags (8 bytes) — 0 for now
    view.setBigUint64(56, 0n, false);

    // MessageId (16 bytes) — random UUID bytes
    const uuid = crypto.getRandomValues(new Uint8Array(16));
    byteArray.set(uuid, 64);

    // PayloadDigest (32 bytes) — zeros (simplified)
    // Production: SHA256 of payload

    // PayloadType (4 bytes) — 1 = Output, 0 = Input
    const payloadType = messageType === SSM_MSG_TYPE.OUTPUT_STREAM_DATA ? 1 : 0;
    view.setUint32(84, payloadType, false);

    // PayloadLength (4 bytes)
    view.setUint32(88, payloadBytes.length, false);

    // Payload
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
