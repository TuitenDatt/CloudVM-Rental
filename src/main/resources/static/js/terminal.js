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

console.log('[SSM] terminal.js v8 loaded — ACK UUID/flags fix');

const SSM_CLIENT_VERSION = '1.2.835.0';

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

const SSM_PAYLOAD_TYPE = {
    OUTPUT:             1,
    ERROR:              2,
    SIZE:               3,
    HANDSHAKE_REQUEST:  5,
    HANDSHAKE_RESPONSE: 6,
    HANDSHAKE_COMPLETE: 7,
    STDERR:             11,
    EXIT_CODE:          12,
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
    container.addEventListener('mousedown', () => term.focus());

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

    let isReadyForInput = false;
    let hasSentInitialResize = false;
    let clientSequenceNumber = 0;
    let sendQueue = Promise.resolve();
    const clientId = generateUUIDv4();

    // ---- WebSocket Events ----

    ws.onopen = function() {
        console.log('[SSM] WebSocket connected, sending token...');

        // Bước 1: Gửi token authentication message dạng JSON String
        const tokenMsg = buildTokenMessage(tokenValue, clientId);
        ws.send(tokenMsg);

    };

    ws.onmessage = async function(event) {
        try {
            const data = event.data;

            // Data có thể là ArrayBuffer (binary) hoặc string (JSON)
            if (data instanceof ArrayBuffer) {
                const result = await handleBinaryMessage(term, ws, data, clientSequenceNumber);
                if (result && result.sentMessages) {
                    clientSequenceNumber += result.sentMessages;
                }
                if (result && result.readyForInput) {
                    isReadyForInput = true;
                    if (!hasSentInitialResize) {
                        await sendTerminalResize(ws, term.cols, term.rows, clientSequenceNumber);
                        clientSequenceNumber++;
                        hasSentInitialResize = true;
                    }
                    term.focus();
                }
            } else if (typeof data === 'string') {
                handleTextMessage(term, ws, data, tokenValue);
            }

        } catch (err) {
            console.error('[SSM] Error handling message:', err);
        }
    };

    ws.onclose = function(event) {
      
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
        if (ws.readyState !== WebSocket.OPEN || !isReadyForInput) return;

        const seqNum = clientSequenceNumber++;
        sendQueue = sendQueue.then(async function() {
            if (ws.readyState !== WebSocket.OPEN) return;
            const inputMsg = await buildInputStreamMessage(inputData, seqNum);
            ws.send(inputMsg);
        }).catch(function(err) {
            console.error('[SSM] Error sending input:', err);
        });
    });

    // ---- Xterm Resize → WebSocket ----
    term.onResize(function(size) {
        if (ws.readyState !== WebSocket.OPEN || !isReadyForInput) return;
        console.log('[SSM] Terminal resized:', size.cols, 'x', size.rows);
        const seqNum = clientSequenceNumber++;
        sendQueue = sendQueue.then(function() {
            return sendTerminalResize(ws, size.cols, size.rows, seqNum);
        }).catch(function(err) {
            console.error('[SSM] Error sending resize:', err);
        });
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
async function handleBinaryMessage(term, ws, data, clientSequenceNumber) {
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

        const payloadType = view.getUint32(112, false);
        // Đọc payload length từ bytes 116-120
        const payloadLength = view.getUint32(116, false); // big-endian

        if (msgType === SSM_MSG_TYPE.OUTPUT_STREAM_DATA && payloadLength > 0) {
            const seqNumInt = Number(serverSeqNum);
            let sentMessages = 0;

            // Luôn gửi ACK trước — kể cả retransmission
            await sendAcknowledge(ws, serverSeqNum, messageId);

            // Dedup: chỉ hiển thị nếu seq này chưa từng nhận
            if (ws._receivedSeqs && ws._receivedSeqs.has(seqNumInt)) {
                console.log('[SSM] Retransmit seq:', seqNumInt, '— ACK sent, display skipped');
                return { sentMessages: sentMessages };
            }
            if (ws._receivedSeqs) ws._receivedSeqs.add(seqNumInt);

            const payload = buffer.slice(SSM_HEADER_SIZE, SSM_HEADER_SIZE + payloadLength);
            const text = new TextDecoder('utf-8').decode(payload);

            if (payloadType === SSM_PAYLOAD_TYPE.HANDSHAKE_REQUEST) {
                const response = buildHandshakeResponse(text);
                const responseMsg = await buildSsmMessage(
                    SSM_MSG_TYPE.INPUT_STREAM_DATA,
                    response,
                    clientSequenceNumber + sentMessages,
                    SSM_PAYLOAD_TYPE.HANDSHAKE_RESPONSE
                );
                ws.send(responseMsg);
                console.log('[SSM] Handshake response sent');
                sentMessages++;
                return { sentMessages: sentMessages };
            }

            if (payloadType === SSM_PAYLOAD_TYPE.HANDSHAKE_COMPLETE) {
                console.log('[SSM] Handshake complete');
                const overlay = document.getElementById('terminal-overlay');
                if (overlay) overlay.classList.add('hidden');
                updateTerminalStatusBadge('connected');
                return { sentMessages: sentMessages, readyForInput: true };
            }

            if (
                payloadType === SSM_PAYLOAD_TYPE.OUTPUT ||
                payloadType === SSM_PAYLOAD_TYPE.STDERR ||
                payloadType === SSM_PAYLOAD_TYPE.EXIT_CODE
            ) {
                term.write(text);
                const overlay = document.getElementById('terminal-overlay');
                if (overlay && !overlay.classList.contains('hidden')) {
                    overlay.classList.add('hidden');
                    updateTerminalStatusBadge('connected');
                }
                return { sentMessages: sentMessages, readyForInput: true };
            }

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

    return null;
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
    // AWS SSM serializes UUID as least-significant 8 bytes, then most-significant 8 bytes.
    const normalized = new Uint8Array(16);
    normalized.set(bytes.slice(8, 16), 0);
    normalized.set(bytes.slice(0, 8), 8);
    const hex = Array.from(normalized).map(b => b.toString(16).padStart(2, '0')).join('');
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20,32)}`;
}

function uuidToSsmBytes(uuid) {
    const clean = uuid.replace(/-/g, '');
    const canonical = new Uint8Array(16);
    for (let i = 0; i < 16; i++) {
        canonical[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
    }

    const ssmBytes = new Uint8Array(16);
    ssmBytes.set(canonical.slice(8, 16), 0);
    ssmBytes.set(canonical.slice(0, 8), 8);
    return ssmBytes;
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
function buildTokenMessage(tokenValue, clientId) {
    const msg = {
        MessageSchemaVersion: "1.0",
        RequestId: generateUUIDv4(),
        TokenValue: tokenValue,
        ClientId: clientId,
        ClientVersion: SSM_CLIENT_VERSION,
        Client: "AWS-Systems-Manager-Session-Manager"
    };
    console.log('[SSM] Token message built with RequestId:', msg.RequestId);
    return JSON.stringify(msg);
}

function buildHandshakeResponse(payloadText) {
    let requestedActions = [];
    try {
        const request = JSON.parse(payloadText);
        requestedActions = request.RequestedClientActions || [];
    } catch (err) {
        console.warn('[SSM] Cannot parse handshake request, sending empty response:', err);
    }

    return JSON.stringify({
        ClientVersion: SSM_CLIENT_VERSION,
        ProcessedClientActions: requestedActions.map(action => ({
            ActionType: action.ActionType,
            ActionStatus: action.ActionType === 'SessionType' ? 1 : 3
        })),
        Errors: []
    });
}

/**
 * Tạo InputStreamData message để gửi keyboard input vào terminal.
 *
 * @param {string} inputText     Text từ Xterm keyboard input
 * @param {number} seqNum        Sequence number
 * @returns {ArrayBuffer}
 */
async function buildInputStreamMessage(inputText, seqNum) {
    return buildSsmMessage(SSM_MSG_TYPE.INPUT_STREAM_DATA, inputText, seqNum, SSM_PAYLOAD_TYPE.OUTPUT);
}

async function sendTerminalResize(ws, cols, rows, seqNum) {
    const resizePayload = JSON.stringify({ cols: cols, rows: rows });
    const resizeMsg = await buildSsmMessage(
        SSM_MSG_TYPE.INPUT_STREAM_DATA,
        resizePayload,
        seqNum,
        SSM_PAYLOAD_TYPE.SIZE
    );
    ws.send(resizeMsg);
    console.log('[SSM] Terminal resize sent:', cols, 'x', rows);
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
async function sendAcknowledge(ws, seqNum, messageId) {
    if (ws.readyState !== WebSocket.OPEN) return;
    const seqNumInt = Number(seqNum);
    const ackPayload = JSON.stringify({
        AcknowledgedMessageType:           SSM_MSG_TYPE.OUTPUT_STREAM_DATA,
        AcknowledgedMessageId:             messageId,
        AcknowledgedMessageSequenceNumber: seqNumInt,
        IsSequentialMessage:               true
    });
    const ackMsg = await buildSsmMessage(SSM_MSG_TYPE.ACKNOWLEDGE, ackPayload, 0, 0, 3n);
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
 * @param {number} [payloadType=0] PayloadType theo AWS SSM data channel
 * @param {bigint} [flags=0n]      Flags theo AWS SSM data channel
 * @returns {ArrayBuffer}
 */
async function buildSsmMessage(messageType, payloadStr, sequenceNumber, payloadType = 0, flags = 0n) {
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

    // Flags (8 bytes)
    view.setBigUint64(56, flags, false);

    // MessageId (16 bytes) — random UUID bytes
    byteArray.set(uuidToSsmBytes(generateUUIDv4()), 64);

    // PayloadDigest (32 bytes) — SHA-256 của payload, giống session-manager-plugin.
    const digest = await crypto.subtle.digest('SHA-256', payloadBytes);
    byteArray.set(new Uint8Array(digest), 80);

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
