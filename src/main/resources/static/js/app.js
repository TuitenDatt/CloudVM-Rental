/**
 * app.js — CloudVM Frontend Application
 *
 * Quản lý:
 * 1. Authentication (Login / Register / Logout)
 * 2. JWT token storage và auto-inject vào requests
 * 3. Render danh sách Packages và Instances
 * 4. Điều hướng giữa các view (SPA navigation)
 * 5. Toast notifications và Modal
 */

'use strict';

// ================================================================
// CONSTANTS & CONFIG
// ================================================================
const API_BASE = '/api';
const TOKEN_KEY = 'cloudvm_token';
const USER_KEY  = 'cloudvm_user';

// Polling interval để refresh instance status (ms)
const POLL_INTERVAL_MS = 15000;

// State
let pollTimer = null;
let selectedPackageForRent = null;

// ================================================================
// UTILITY FUNCTIONS
// ================================================================

/**
 * Lấy JWT token từ localStorage.
 * @returns {string|null}
 */
function getToken() {
    return localStorage.getItem(TOKEN_KEY);
}

/**
 * Lấy thông tin user đã lưu.
 * @returns {Object|null}
 */
function getUser() {
    const raw = localStorage.getItem(USER_KEY);
    return raw ? JSON.parse(raw) : null;
}

/**
 * Lưu auth info sau khi login/register thành công.
 * @param {Object} authData  Response data từ API auth
 */
function saveAuth(authData) {
    localStorage.setItem(TOKEN_KEY, authData.token);
    localStorage.setItem(USER_KEY, JSON.stringify({
        userId:   authData.userId,
        username: authData.username,
        email:    authData.email,
    }));
}

/**
 * Xóa auth info khi logout.
 */
function clearAuth() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
}

/**
 * Fetch wrapper tự động inject Authorization header và parse JSON.
 *
 * @param {string} url     API endpoint
 * @param {Object} options fetch options
 * @returns {Promise<Object>} Parsed JSON response
 * @throws {Error} nếu response không OK
 */
async function apiFetch(url, options = {}) {
    const token = getToken();
    const headers = {
        'Content-Type': 'application/json',
        ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
        ...(options.headers || {}),
    };

    const response = await fetch(`${API_BASE}${url}`, {
        ...options,
        headers,
    });

    // Parse JSON an toàn: tránh crash khi body rỗng (204 / DELETE thành công)
    let data = null;
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
        const text = await response.text();
        if (text) {
            data = JSON.parse(text);
        }
    }

    if (!response.ok) {
        throw new Error((data && data.message) || `HTTP ${response.status}`);
    }

    return data;
}


/**
 * Format giá tiền sang VND.
 * @param {number} amount
 * @returns {string}
 */
function formatPrice(amount) {
    return new Intl.NumberFormat('vi-VN', {
        style: 'currency',
        currency: 'VND',
    }).format(amount);
}

/**
 * Format datetime sang dạng dễ đọc.
 * @param {string} isoString
 * @returns {string}
 */
function formatDateTime(isoString) {
    if (!isoString) return '—';
    return new Date(isoString).toLocaleString('vi-VN', {
        day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit',
    });
}

/**
 * Tính thời gian còn lại đến expire_date.
 * @param {string} expireDate
 * @returns {string}
 */
function formatTimeRemaining(expireDate) {
    const diff = new Date(expireDate) - new Date();
    if (diff <= 0) return 'Đã hết hạn';
    const days    = Math.floor(diff / 86400000);
    const hours   = Math.floor((diff % 86400000) / 3600000);
    const minutes = Math.floor((diff % 3600000) / 60000);
    if (days > 0) return `${days} ngày ${hours} giờ`;
    if (hours > 0) return `${hours} giờ ${minutes} phút`;
    return `${minutes} phút`;
}

// ================================================================
// TOAST NOTIFICATIONS
// ================================================================

/**
 * Hiển thị toast notification.
 * @param {string} message  Nội dung thông báo
 * @param {'success'|'error'|'info'|'warning'} type  Loại thông báo
 * @param {number} duration Thời gian hiển thị (ms)
 */
function showToast(message, type = 'info', duration = 4000) {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.animation = 'fadeIn 0.2s ease reverse';
        setTimeout(() => toast.remove(), 200);
    }, duration);
}

// ================================================================
// MODAL
// ================================================================

function openModal(id) {
    document.getElementById(id).classList.remove('hidden');
}

function closeModal(id) {
    document.getElementById(id).classList.add('hidden');
}

// ================================================================
// VIEW NAVIGATION (SPA)
// ================================================================

/**
 * Chuyển sang view chỉ định.
 * @param {'auth'|'dashboard'|'terminal'} viewName
 */
function showView(viewName) {
    document.querySelectorAll('.view').forEach(v => {
        v.classList.remove('active');
    });
    const target = document.getElementById(`view-${viewName}`);
    if (target) {
        target.classList.add('active');
    }
}

/**
 * Chuyển section trong dashboard.
 * @param {'instances'|'packages'} sectionName
 */
function showSection(sectionName) {
    document.querySelectorAll('.content-section').forEach(s => {
        s.classList.remove('active');
    });
    document.querySelectorAll('.nav-item').forEach(n => {
        n.classList.remove('active');
    });

    document.getElementById(`section-${sectionName}`).classList.add('active');
    document.getElementById(`nav-${sectionName}`).classList.add('active');

    if (sectionName === 'packages') {
        loadPackages();
    }
}

// ================================================================
// AUTH FUNCTIONS
// ================================================================

/**
 * Chuyển tab Login/Register trong auth view.
 * @param {'login'|'register'} tab
 */
function switchAuthTab(tab) {
    document.querySelectorAll('.auth-tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.auth-form').forEach(f => f.classList.remove('active'));

    document.getElementById(`tab-${tab}`).classList.add('active');
    document.getElementById(`form-${tab}`).classList.add('active');
}

/**
 * Xử lý đăng nhập.
 * @param {Event} event
 */
async function handleLogin(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-login');
    const errorEl = document.getElementById('login-error');

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        const data = await apiFetch('/auth/login', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('login-username').value,
                password: document.getElementById('login-password').value,
            }),
        });

        saveAuth(data.data);
        initDashboard();
        showView('dashboard');
        showToast(`Chào mừng trở lại, ${data.data.username}!`, 'success');

    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
}

/**
 * Xử lý đăng ký.
 * @param {Event} event
 */
async function handleRegister(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-register');
    const errorEl = document.getElementById('register-error');

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        const data = await apiFetch('/auth/register', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('reg-username').value,
                email:    document.getElementById('reg-email').value,
                password: document.getElementById('reg-password').value,
            }),
        });

        saveAuth(data.data);
        initDashboard();
        showView('dashboard');
        showToast(`Đăng ký thành công! Chào mừng ${data.data.username}!`, 'success');

    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
}

/**
 * Đăng xuất.
 */
function handleLogout() {
    stopPolling();
    clearAuth();
    showView('auth');
    showToast('Đã đăng xuất thành công.', 'info');
}

// ================================================================
// DASHBOARD INIT
// ================================================================

/**
 * Khởi tạo dashboard sau khi login thành công.
 */
function initDashboard() {
    const user = getUser();
    if (!user) return;

    // Cập nhật sidebar user info
    document.getElementById('sidebar-username').textContent = user.username;
    document.getElementById('sidebar-avatar').textContent = user.username.charAt(0).toUpperCase();

    // Load instances và bắt đầu polling
    loadInstances();
    startPolling();
}

// ================================================================
// INSTANCES
// ================================================================

/**
 * Load và render danh sách instance của user.
 */
async function loadInstances() {
    const container = document.getElementById('instances-list');
    container.innerHTML = `
        <div class="loading-state">
            <svg class="spinner large" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none" stroke-dasharray="31.4" stroke-dashoffset="31.4"><animate attributeName="stroke-dashoffset" values="31.4;0;31.4" dur="1.2s" repeatCount="indefinite"/></circle></svg>
            <span>Đang tải danh sách máy ảo...</span>
        </div>`;

    try {
        const data = await apiFetch('/instances');
        const instances = data.data || [];
        renderInstances(instances);
        updateStats(instances);
    } catch (error) {
        container.innerHTML = `<div class="empty-state">⚠️ ${error.message}</div>`;
        if (error.message.includes('401') || error.message.includes('403')) {
            handleLogout();
        }
    }
}

/**
 * Render danh sách instance cards.
 * @param {Array} instances
 */
function renderInstances(instances) {
    const container = document.getElementById('instances-list');

    if (instances.length === 0) {
        container.innerHTML = `
            <div class="empty-state">
                <svg style="width:48px;height:48px;opacity:0.3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1">
                    <rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/>
                </svg>
                <span>Bạn chưa có máy ảo nào.</span>
                <button class="btn btn-primary" onclick="showSection('packages')">Thuê máy đầu tiên</button>
            </div>`;
        return;
    }

    container.innerHTML = instances.map(inst => renderInstanceCard(inst)).join('');
}

/**
 * Render HTML cho một instance card.
 * @param {Object} inst  Instance data từ API
 * @returns {string}
 */
function renderInstanceCard(inst) {
    const statusClass = getStatusClass(inst.status);
    const statusLabel = getStatusLabel(inst.status);
    const canOpenTerminal = inst.status === 'RUNNING';

    return `
        <div class="instance-card" id="instance-card-${inst.id}">
            <div class="instance-card-header">
                <div>
                    <div class="instance-card-title">${escapeHtml(inst.packageName)}</div>
                    <div class="instance-card-subtitle">${inst.awsInstanceId || 'Đang khởi tạo...'}</div>
                </div>
                <span class="status-badge ${statusClass}">${statusLabel}</span>
            </div>
            <div class="instance-card-body">
                <div class="instance-info-row">
                    <span class="instance-info-label">Loại máy</span>
                    <span class="instance-info-value">${escapeHtml(inst.instanceType)}</span>
                </div>
                <div class="instance-info-row">
                    <span class="instance-info-label">IP công khai</span>
                    <span class="instance-info-value">${inst.publicIp || '—'}</span>
                </div>
                <div class="instance-info-row">
                    <span class="instance-info-label">Hết hạn</span>
                    <span class="instance-info-value">${formatDateTime(inst.expireDate)}</span>
                </div>
                <div class="instance-info-row">
                    <span class="instance-info-label">Còn lại</span>
                    <span class="instance-info-value">${formatTimeRemaining(inst.expireDate)}</span>
                </div>
            </div>
            <div class="instance-card-actions">
                <button
                    id="btn-terminal-${inst.id}"
                    class="btn btn-primary"
                    onclick="openTerminal(${inst.id}, '${escapeHtml(inst.packageName)}')"
                    ${canOpenTerminal ? '' : 'disabled style="opacity:0.4;cursor:not-allowed"'}
                >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="4 17 10 11 4 5"/><line x1="12" y1="19" x2="20" y2="19"/>
                    </svg>
                    Terminal
                </button>
                <button class="btn btn-ghost" onclick="loadInstances()">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/>
                    </svg>
                    Refresh
                </button>
                ${inst.status !== 'TERMINATED' ? `
                <button class="btn btn-delete" onclick="handleTerminateInstance(${inst.id})">
                    Xóa
                </button>
                ` : ''}
            </div>
        </div>`;
}

/**
 * Cập nhật stats bar.
 * @param {Array} instances
 */
function updateStats(instances) {
    const running  = instances.filter(i => i.status === 'RUNNING').length;
    const pending  = instances.filter(i => i.status === 'PENDING').length;
    const expired  = instances.filter(i => i.status === 'STOPPED_EXPIRED').length;

    document.getElementById('stat-running').textContent = running;
    document.getElementById('stat-pending').textContent = pending;
    document.getElementById('stat-expired').textContent = expired;
}

/**
 * Xử lý hủy máy ảo.
 */
async function handleTerminateInstance(instanceId) {
    if (!confirm('Bạn có chắc chắn muốn XÓA VĨNH VIỄN máy ảo này không? Hành động này sẽ gọi lệnh lên AWS để terminate máy, toàn bộ dữ liệu trên máy sẽ bị xóa sạch!')) {
        return;
    }

    try {
        const data = await apiFetch(`/instances/${instanceId}`, {
            method: 'DELETE',
        });
        showToast((data && data.message) || 'Đã hủy máy thành công', 'success');
        loadInstances();
    } catch (error) {
        showToast(error.message, 'error');
    }
}

/**
 * Map status enum sang CSS class.
 */
function getStatusClass(status) {
    const map = {
        'RUNNING':          'status-running',
        'PENDING':          'status-pending',
        'STOPPED_EXPIRED':  'status-stopped',
        'TERMINATED':       'status-terminated',
    };
    return map[status] || 'status-terminated';
}

/**
 * Map status enum sang tiếng Việt.
 */
function getStatusLabel(status) {
    const map = {
        'RUNNING':          'Đang chạy',
        'PENDING':          'Đang khởi tạo',
        'STOPPED_EXPIRED':  'Đã hết hạn',
        'TERMINATED':       'Đã xóa',
    };
    return map[status] || status;
}

// ================================================================
// PACKAGES
// ================================================================

/**
 * Load và render danh sách gói cước.
 */
async function loadPackages() {
    const container = document.getElementById('packages-grid');
    container.innerHTML = `
        <div class="loading-state">
            <svg class="spinner large" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="2" fill="none" stroke-dasharray="31.4" stroke-dashoffset="31.4"><animate attributeName="stroke-dashoffset" values="31.4;0;31.4" dur="1.2s" repeatCount="indefinite"/></circle></svg>
            <span>Đang tải gói cước...</span>
        </div>`;

    try {
        const data = await apiFetch('/packages');
        const packages = data.data || [];
        renderPackages(packages);
    } catch (error) {
        container.innerHTML = `<div class="empty-state">⚠️ ${error.message}</div>`;
    }
}

/**
 * Render danh sách package cards.
 * @param {Array} packages
 */
function renderPackages(packages) {
    const container = document.getElementById('packages-grid');

    if (packages.length === 0) {
        container.innerHTML = '<div class="empty-state">Chưa có gói cước nào.</div>';
        return;
    }

    container.innerHTML = packages.map(pkg => `
        <div class="package-card">
            <div class="package-card-name">${escapeHtml(pkg.packageName)}</div>
            <div class="package-card-price">
                ${formatPrice(pkg.price)}<span>/gói</span>
            </div>
            <ul class="package-card-features">
                <li>Windows Server (${escapeHtml(pkg.instanceType)})</li>
                <li>Thời hạn ${pkg.durationDays} ngày</li>
                <li>Terminal qua trình duyệt</li>
                <li>Không cần cài phần mềm</li>
            </ul>
            <button
                id="btn-rent-pkg-${pkg.id}"
                class="btn btn-primary btn-full"
                onclick="showRentModal(${pkg.id}, '${escapeHtml(pkg.packageName)}', ${pkg.price}, ${pkg.durationDays})">
                Thuê ngay
            </button>
        </div>`).join('');
}

/**
 * Hiển thị modal xác nhận thuê máy.
 */
function showRentModal(packageId, packageName, price, durationDays) {
    selectedPackageForRent = packageId;

    document.getElementById('modal-rent-content').innerHTML = `
        <p>Bạn sắp thuê gói:</p>
        <br>
        <p><strong>${escapeHtml(packageName)}</strong></p>
        <p>Giá: <strong>${formatPrice(price)}</strong></p>
        <p>Thời hạn: <strong>${durationDays} ngày</strong></p>
        <br>
        <p style="color:var(--color-warning);font-size:12px;">
            ⚠️ Máy sẽ được khởi tạo trong vài phút sau khi xác nhận.
        </p>`;

    openModal('modal-rent');
}

/**
 * Xác nhận thuê máy.
 */
async function confirmRent() {
    if (!selectedPackageForRent) return;

    const btn = document.getElementById('btn-confirm-rent');
    setButtonLoading(btn, true);

    try {
        const data = await apiFetch('/instances/rent', {
            method: 'POST',
            body: JSON.stringify({ packageId: selectedPackageForRent }),
        });

        closeModal('modal-rent');
        showToast('Yêu cầu đã được gửi! Máy ảo đang được khởi tạo...', 'success');
        showSection('instances');
        loadInstances();

    } catch (error) {
        closeModal('modal-rent');
        showToast(error.message, 'error');
    } finally {
        setButtonLoading(btn, false);
        selectedPackageForRent = null;
    }
}

// ================================================================
// TERMINAL
// ================================================================

/**
 * Mở SSM Web Terminal cho một instance.
 * Gọi API để lấy streamUrl + tokenValue, sau đó init Xterm.
 *
 * @param {number} instanceId  DB instance ID
 * @param {string} label       Tên hiển thị cho terminal header
 */
async function openTerminal(instanceId, label) {
    showView('terminal');
    document.getElementById('terminal-instance-label').textContent = label;
    document.getElementById('terminal-overlay').classList.remove('hidden');
    document.getElementById('terminal-overlay-text').textContent = 'Đang tạo SSM session...';
    document.getElementById('terminal-status-badge').className = 'status-badge status-pending';
    document.getElementById('terminal-status-badge').textContent = 'Đang kết nối...';

    try {
        const data = await apiFetch(`/ssm/session/${instanceId}`, { method: 'POST' });
        const session = data.data;

        document.getElementById('terminal-overlay-text').textContent = 'Đang kết nối WebSocket...';

        // Khởi tạo Xterm.js và kết nối WebSocket SSM
        initTerminal(session.streamUrl, session.tokenValue, session.sessionId);

    } catch (error) {
        document.getElementById('terminal-overlay-text').textContent = `Lỗi: ${error.message}`;
        showToast(`Không thể mở terminal: ${error.message}`, 'error');
    }
}

/**
 * Đóng terminal và quay về dashboard.
 */
function closeTerminal() {
    if (window.activeTerminal) {
        window.activeTerminal.dispose();
        window.activeTerminal = null;
    }
    if (window.activeWs) {
        window.activeWs.close();
        window.activeWs = null;
    }
    document.getElementById('terminal-container').innerHTML = '';
    showView('dashboard');
}

// ================================================================
// POLLING
// ================================================================

/**
 * Bắt đầu tự động refresh instances để cập nhật status PENDING → RUNNING.
 */
function startPolling() {
    stopPolling();
    pollTimer = setInterval(() => {
        // Chỉ poll khi đang ở dashboard instances section
        const section = document.getElementById('section-instances');
        if (section && section.classList.contains('active')
                && document.getElementById('view-dashboard').classList.contains('active')) {
            loadInstances();
        }
    }, POLL_INTERVAL_MS);
}

/**
 * Dừng polling khi logout hoặc không cần nữa.
 */
function stopPolling() {
    if (pollTimer) {
        clearInterval(pollTimer);
        pollTimer = null;
    }
}

// ================================================================
// UI HELPERS
// ================================================================

/**
 * Toggle trạng thái loading của button.
 * @param {HTMLButtonElement} btn
 * @param {boolean} loading
 */
function setButtonLoading(btn, loading) {
    const textEl    = btn.querySelector('.btn-text');
    const loadingEl = btn.querySelector('.btn-loading');
    btn.disabled = loading;
    if (textEl)    textEl.classList.toggle('hidden', loading);
    if (loadingEl) loadingEl.classList.toggle('hidden', !loading);
}

/**
 * Escape HTML để tránh XSS.
 * @param {string} str
 * @returns {string}
 */
function escapeHtml(str) {
    if (!str) return '';
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// ================================================================
// INIT — Chạy khi DOM loaded
// ================================================================

document.addEventListener('DOMContentLoaded', () => {
    // Kiểm tra nếu đã có token hợp lệ → vào dashboard ngay
    const token = getToken();
    if (token) {
        initDashboard();
        showView('dashboard');
    } else {
        showView('auth');
    }

    // Đóng modal khi click overlay
    document.getElementById('modal-rent').addEventListener('click', function(e) {
        if (e.target === this) closeModal('modal-rent');
    });
});
