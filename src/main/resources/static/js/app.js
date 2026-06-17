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
const REFRESH_TOKEN_KEY = 'cloudvm_refresh_token';
const USER_KEY  = 'cloudvm_user';
const PROFILE_PREFS_KEY = 'cloudvm_profile_prefs';

// Polling interval để refresh instance status (ms)
const POLL_INTERVAL_MS = 15000;

// State
let pollTimer = null;
let selectedPackageForRent = null;
let refreshPromise = null;

function getProfilePreferences() {
    const raw = localStorage.getItem(PROFILE_PREFS_KEY);
    if (!raw) {
        return {
            autoRefresh: true,
            toasts: true,
        };
    }

    try {
        return {
            autoRefresh: true,
            toasts: true,
            ...JSON.parse(raw),
        };
    } catch (_) {
        return {
            autoRefresh: true,
            toasts: true,
        };
    }
}

function saveProfilePreferences(prefs) {
    localStorage.setItem(PROFILE_PREFS_KEY, JSON.stringify(prefs));
}

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

function getRefreshToken() {
    return localStorage.getItem(REFRESH_TOKEN_KEY);
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
    localStorage.setItem(REFRESH_TOKEN_KEY, authData.refreshToken);
    localStorage.setItem(USER_KEY, JSON.stringify({
        userId:   authData.userId,
        username: authData.username,
        email:    authData.email,
        authProvider: authData.authProvider,
    }));
}

/**
 * Xóa auth info khi logout.
 */
function clearAuth() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
}

function updateStoredUser(profile) {
    const current = getUser() || {};
    localStorage.setItem(USER_KEY, JSON.stringify({
        ...current,
        userId: profile.userId,
        username: profile.username,
        email: profile.email,
    }));
    renderSidebarUser();
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
    if (!getProfilePreferences().toasts && type !== 'error') {
        return;
    }

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

function togglePasswordVisibility(button) {
    const inputId = button.dataset.target;
    const input = document.getElementById(inputId);
    if (!input) return;

    const nextType = input.type === 'password' ? 'text' : 'password';
    input.type = nextType;
    button.textContent = nextType === 'password' ? 'Hien' : 'An';
}

function evaluatePasswordStrength(password) {
    if (!password) {
        return {
            className: 'strength-empty',
            label: 'Độ mạnh mật khẩu',
            hint: 'Dùng ít nhất 8 ký tự, kết hợp chữ hoa, chữ thường, số và ký tự đặc biệt.',
        };
    }

    let score = 0;
    if (password.length >= 8) score += 1;
    if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 1;
    if (/\d/.test(password)) score += 1;
    if (/[^A-Za-z0-9]/.test(password) || password.length >= 12) score += 1;

    if (score <= 1) {
        return {
            className: 'strength-weak',
            label: 'Yếu',
            hint: 'Thêm độ dài và kết hợp nhiều loại ký tự hơn.',
        };
    }
    if (score === 2) {
        return {
            className: 'strength-fair',
            label: 'Trung bình',
            hint: 'Nên thêm chữ hoa, số hoặc ký tự đặc biệt.',
        };
    }
    if (score === 3) {
        return {
            className: 'strength-good',
            label: 'Tốt',
            hint: 'Mật khẩu khá tốt. Thêm ký tự đặc biệt để mạnh hơn.',
        };
    }
    return {
        className: 'strength-strong',
        label: 'Mạnh',
        hint: 'Mật khẩu mạnh, phù hợp để sử dụng.',
    };
}

function updateRegisterPasswordStrength() {
    const input = document.getElementById('reg-password');
    const bar = document.getElementById('reg-password-strength-bar');
    const label = document.getElementById('reg-password-strength-label');
    const hint = document.getElementById('reg-password-strength-hint');

    if (!input || !bar || !label || !hint) {
        return;
    }

    const strength = evaluatePasswordStrength(input.value);
    bar.className = `password-strength-fill ${strength.className}`;
    label.textContent = strength.label;
    hint.textContent = strength.hint;
}

function validateRegisterPasswords() {
    const password = document.getElementById('reg-password').value;
    const confirmPassword = document.getElementById('reg-confirm-password').value;

    if (password !== confirmPassword) {
        throw new Error('Mat khau xac nhan khong khop');
    }
}

function initAuthPasswordEnhancements() {
    const registerPassword = document.getElementById('reg-password');
    const confirmPassword = document.getElementById('reg-confirm-password');

    if (registerPassword) {
        registerPassword.addEventListener('input', updateRegisterPasswordStrength);
    }

    if (confirmPassword) {
        confirmPassword.addEventListener('paste', () => {
            setTimeout(validateRegisterPasswordMatchHint, 0);
        });
        confirmPassword.addEventListener('input', validateRegisterPasswordMatchHint);
    }

    if (registerPassword) {
        registerPassword.addEventListener('input', validateRegisterPasswordMatchHint);
    }

    updateRegisterPasswordStrength();
}

function validateRegisterPasswordMatchHint() {
    const errorEl = document.getElementById('register-error');
    const password = document.getElementById('reg-password')?.value || '';
    const confirmPassword = document.getElementById('reg-confirm-password')?.value || '';

    if (!errorEl || !confirmPassword) {
        return;
    }

    if (confirmPassword && password !== confirmPassword) {
        errorEl.textContent = 'Mat khau xac nhan khong khop';
        errorEl.classList.remove('hidden');
        return;
    }

    if (errorEl.textContent === 'Mat khau xac nhan khong khop') {
        errorEl.classList.add('hidden');
        errorEl.textContent = '';
    }
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

function loginWithGoogle() {
    window.location.href = '/oauth2/authorization/google';
}

function handleOAuthRedirect() {
    if (!window.location.hash || !window.location.hash.includes('oauth=')) {
        return false;
    }

    const params = new URLSearchParams(window.location.hash.substring(1));
    const status = params.get('oauth');

    if (status === 'success') {
        saveAuth({
            token: params.get('token'),
            refreshToken: params.get('refreshToken'),
            userId: Number(params.get('userId')),
            username: params.get('username'),
            email: params.get('email'),
            authProvider: params.get('authProvider'),
        });
        history.replaceState(null, '', window.location.pathname);
        initDashboard();
        showView('dashboard');
        showToast(`Chào mừng, ${params.get('username')}!`, 'success');
        return true;
    }

    history.replaceState(null, '', window.location.pathname);
    showView('auth');
    showToast('Không thể đăng nhập bằng Google.', 'error');
    return true;
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
    errorEl.textContent = '';

    try {
        validateRegisterPasswords();

        const data = await apiFetch('/auth/register', {
            method: 'POST',
            body: JSON.stringify({
                username: document.getElementById('reg-username').value,
                email:    document.getElementById('reg-email').value,
                password: document.getElementById('reg-password').value,
            }),
        });
        switchAuthTab('login');
        document.getElementById('login-username').value = document.getElementById('reg-username').value;
        document.getElementById('reg-password').value = '';
        document.getElementById('reg-confirm-password').value = '';
        updateRegisterPasswordStrength();
        showToast(data.message || 'Dang ky thanh cong. Ban co the dang nhap ngay.', 'success');

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
async function handleLogout() {
    const refreshToken = getRefreshToken();
    if (refreshToken) {
        try {
            await fetch(`${API_BASE}/auth/logout`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({ refreshToken }),
            });
        } catch (_) {
            // Ignore logout sync failures and clear local state anyway.
        }
    }

    stopPolling();
    clearAuth();
    showView('auth');
    showToast('Đã đăng xuất thành công.', 'info');
}

async function openProfileModal() {
    const user = getUser();
    if (user) {
        populateProfile({
            userId: user.userId,
            username: user.username,
            email: user.email,
        });
    }

    switchProfileSection('overview');
    syncProfilePreferencesUi();
    document.getElementById('profile-error').classList.add('hidden');
    document.getElementById('profile-password-error').classList.add('hidden');
    document.getElementById('profile-current-password').value = '';
    document.getElementById('profile-new-password').value = '';
    openModal('modal-profile');

    try {
        const data = await apiFetch('/profile');
        populateProfile(data.data);
        updateStoredUser(data.data);
        await loadProfileUsage();
    } catch (error) {
        showToast(error.message, 'error');
    }
}

function switchProfileSection(sectionName) {
    document.querySelectorAll('.settings-nav-item').forEach(item => item.classList.remove('active'));
    document.querySelectorAll('.settings-section').forEach(section => section.classList.remove('active'));

    const tab = document.getElementById(`settings-tab-${sectionName}`);
    const section = document.getElementById(`profile-section-${sectionName}`);
    if (tab) tab.classList.add('active');
    if (section) section.classList.add('active');
}

function syncProfilePreferencesUi() {
    const prefs = getProfilePreferences();
    document.getElementById('pref-auto-refresh').checked = prefs.autoRefresh;
    document.getElementById('pref-toasts').checked = prefs.toasts;
}

function handlePreferenceChange() {
    const prefs = {
        autoRefresh: document.getElementById('pref-auto-refresh').checked,
        toasts: document.getElementById('pref-toasts').checked,
    };
    saveProfilePreferences(prefs);
    showToast('Da cap nhat tuy chon.', 'success');
}

function populateProfile(profile) {
    document.getElementById('profile-username').textContent = profile.username || 'User';
    document.getElementById('profile-avatar').textContent = (profile.username || 'U').charAt(0).toUpperCase();
    document.getElementById('profile-username-input').value = profile.username || '';
    document.getElementById('profile-username-readonly').textContent = profile.username || 'User';
    document.getElementById('profile-email-readonly').textContent = profile.email || '-';
    document.getElementById('profile-created').textContent = profile.createdAt
        ? `Tạo lúc ${formatDateTime(profile.createdAt)}`
        : 'Tài khoản CloudVM';
}

function populateUsageFromInstances(instances) {
    const runningInstances = instances.filter(instance => instance.status === 'RUNNING');
    const pendingInstances = instances.filter(instance => instance.status === 'PENDING');
    const expiredInstances = instances.filter(instance => instance.status === 'STOPPED_EXPIRED');
    const activeInstances = instances.filter(instance =>
        instance.status === 'RUNNING' || instance.status === 'PENDING' || instance.status === 'STOPPED_EXPIRED'
    );

    document.getElementById('usage-running').textContent = runningInstances.length;
    document.getElementById('usage-pending').textContent = pendingInstances.length;
    document.getElementById('usage-expired').textContent = expiredInstances.length;
    document.getElementById('usage-total').textContent = instances.length;

    const quotaLimit = 2;
    document.getElementById('usage-quota').textContent = `${activeInstances.length} / ${quotaLimit} may`;
    document.getElementById('usage-quota-bar').style.width =
        `${Math.min(100, Math.round((activeInstances.length / quotaLimit) * 100))}%`;

    const runningList = document.getElementById('usage-running-list');
    const runningCount = document.getElementById('usage-running-list-count');
    runningCount.textContent = `${runningInstances.length} may`;

    if (runningInstances.length === 0) {
        runningList.innerHTML = '<div class="usage-empty">Chua co may ao dang chay.</div>';
        return;
    }

    runningList.innerHTML = runningInstances.map(instance => `
        <div class="usage-instance-item">
            <div class="usage-instance-main">
                <div class="usage-instance-name">${escapeHtml(instance.packageName || 'CloudVM instance')}</div>
                <div class="usage-instance-meta">
                    ${escapeHtml(instance.instanceType || '-')} | ${instance.publicIp || 'Chua co IP'}
                </div>
            </div>
            <span class="status-badge status-running">Dang chay</span>
        </div>
    `).join('');
}

async function loadProfileUsage() {
    try {
        const data = await apiFetch('/instances');
        populateUsageFromInstances(data.data || []);
    } catch (error) {
        document.getElementById('usage-running-list').innerHTML =
            `<div class="usage-empty">${escapeHtml(error.message)}</div>`;
    }
}

async function handleUpdateProfile(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-save-profile');
    const errorEl = document.getElementById('profile-error');

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        const data = await apiFetch('/profile', {
            method: 'PUT',
            body: JSON.stringify({
                username: document.getElementById('profile-username-input').value,
            }),
        });
        populateProfile(data.data);
        updateStoredUser(data.data);
        showToast('Đã cập nhật hồ sơ.', 'success');
    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
}

async function handleChangePassword(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-change-password');
    const errorEl = document.getElementById('profile-password-error');
    const currentPassword = document.getElementById('profile-current-password').value;
    const newPassword = document.getElementById('profile-new-password').value;

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        if (!currentPassword || !newPassword) {
            throw new Error('Vui lòng nhập đủ mật khẩu hiện tại và mật khẩu mới');
        }

        await apiFetch('/profile/password', {
            method: 'PUT',
            body: JSON.stringify({ currentPassword, newPassword }),
        });

        document.getElementById('profile-current-password').value = '';
        document.getElementById('profile-new-password').value = '';
        showToast('Đã đổi mật khẩu.', 'success');
    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
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
    renderSidebarUser();

    // Load instances và bắt đầu polling
    loadInstances();
    startPolling();
}

function renderSidebarUser() {
    const user = getUser();
    if (!user) return;

    document.getElementById('sidebar-username').textContent = user.username;
    document.getElementById('sidebar-avatar').textContent = user.username.charAt(0).toUpperCase();
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
        if (!getProfilePreferences().autoRefresh) {
            return;
        }

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
    if (handleOAuthRedirect()) {
        return;
    }

    initAuthPasswordEnhancements();

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

document.getElementById('modal-profile').addEventListener('click', function(e) {
        if (e.target === this) closeModal('modal-profile');
    });
});

async function parseApiResponse(response) {
    let data = null;
    const contentType = response.headers.get('content-type');
    if (contentType && contentType.includes('application/json')) {
        const text = await response.text();
        if (text) {
            data = JSON.parse(text);
        }
    }
    return data;
}

async function refreshAccessToken() {
    if (refreshPromise) {
        return refreshPromise;
    }

    const refreshToken = getRefreshToken();
    if (!refreshToken) {
        throw new Error('Phien dang nhap da het han');
    }

    refreshPromise = (async () => {
        const response = await fetch(`${API_BASE}/auth/refresh`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ refreshToken }),
        });
        const data = await parseApiResponse(response);

        if (!response.ok) {
            throw new Error((data && data.message) || 'Khong the lam moi token');
        }
        saveAuth(data.data);
        return data.data.token;
    })();

    try {
        return await refreshPromise;
    } finally {
        refreshPromise = null;
    }
}

async function apiFetchWithRetry(url, options = {}, allowRefresh = true) {
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
    const data = await parseApiResponse(response);

    if (response.status === 401 && allowRefresh && !url.startsWith('/auth/')) {
        try {
            await refreshAccessToken();
            return apiFetchWithRetry(url, options, false);
        } catch (error) {
            clearAuth();
            throw error;
        }
    }

    if (!response.ok) {
        throw new Error((data && data.message) || `HTTP ${response.status}`);
    }

    return data;
}

async function apiFetch(url, options = {}) {
    return apiFetchWithRetry(url, options, true);
}

function openForgotPasswordModal() {
    document.getElementById('forgot-password-error').classList.add('hidden');
    document.getElementById('forgot-email').value = '';
    openModal('modal-forgot-password');
}

async function handleForgotPassword(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-forgot-password');
    const errorEl = document.getElementById('forgot-password-error');

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        const data = await apiFetch('/auth/forgot-password', {
            method: 'POST',
            body: JSON.stringify({
                email: document.getElementById('forgot-email').value,
            }),
        });
        closeModal('modal-forgot-password');
        showToast(data.message || 'Da gui email dat lai mat khau.', 'success');
    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
}

function openResetPasswordModal(token) {
    document.getElementById('reset-password-token').value = token;
    document.getElementById('reset-new-password').value = '';
    document.getElementById('reset-password-error').classList.add('hidden');
    openModal('modal-reset-password');
}

async function handleResetPassword(event) {
    event.preventDefault();
    const btn = document.getElementById('btn-reset-password');
    const errorEl = document.getElementById('reset-password-error');

    setButtonLoading(btn, true);
    errorEl.classList.add('hidden');

    try {
        const data = await apiFetch('/auth/reset-password', {
            method: 'POST',
            body: JSON.stringify({
                token: document.getElementById('reset-password-token').value,
                newPassword: document.getElementById('reset-new-password').value,
            }),
        });
        closeModal('modal-reset-password');
        const url = new URL(window.location.href);
        url.searchParams.delete('resetToken');
        history.replaceState(null, '', url.pathname + url.search);
        switchAuthTab('login');
        showView('auth');
        showToast(data.message || 'Dat lai mat khau thanh cong.', 'success');
    } catch (error) {
        errorEl.textContent = error.message;
        errorEl.classList.remove('hidden');
    } finally {
        setButtonLoading(btn, false);
    }
}

async function handleAuthLinkActions() {
    const url = new URL(window.location.href);
    const verifyToken = url.searchParams.get('verifyToken');
    const resetToken = url.searchParams.get('resetToken');

    if (verifyToken) {
        try {
            const data = await apiFetch('/auth/verify-email', {
                method: 'POST',
                body: JSON.stringify({ token: verifyToken }),
            });
            showToast(data.message || 'Xac thuc email thanh cong.', 'success');
        } catch (error) {
            showToast(error.message, 'error');
        }
        url.searchParams.delete('verifyToken');
        history.replaceState(null, '', url.pathname + url.search);
        switchAuthTab('login');
        showView('auth');
    }

    if (resetToken) {
        switchAuthTab('login');
        showView('auth');
        openResetPasswordModal(resetToken);
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const forgotModal = document.getElementById('modal-forgot-password');
    if (forgotModal) {
        forgotModal.addEventListener('click', function(e) {
            if (e.target === this) closeModal('modal-forgot-password');
        });
    }

    const resetModal = document.getElementById('modal-reset-password');
    if (resetModal) {
        resetModal.addEventListener('click', function(e) {
            if (e.target === this) closeModal('modal-reset-password');
        });
    }

    handleAuthLinkActions();
});

function handlePreferenceChange() {
    const prefs = {
        autoRefresh: document.getElementById('pref-auto-refresh').checked,
        toasts: document.getElementById('pref-toasts').checked,
    };
    saveProfilePreferences(prefs);
    showToast('Đã cập nhật tùy chọn.', 'success');
}

function populateProfile(profile) {
    document.getElementById('profile-username').textContent = profile.username || 'User';
    document.getElementById('profile-avatar').textContent = (profile.username || 'U').charAt(0).toUpperCase();
    document.getElementById('profile-username-input').value = profile.username || '';
    document.getElementById('profile-username-readonly').textContent = profile.username || 'User';
    document.getElementById('profile-email-readonly').textContent = profile.email || '-';
    document.getElementById('profile-created').textContent = profile.createdAt
        ? `Tạo lúc ${formatDateTime(profile.createdAt)}`
        : 'Tài khoản CloudVM';
}

function populateUsageFromInstances(instances) {
    const runningInstances = instances.filter(instance => instance.status === 'RUNNING');
    const pendingInstances = instances.filter(instance => instance.status === 'PENDING');
    const expiredInstances = instances.filter(instance => instance.status === 'STOPPED_EXPIRED');

    document.getElementById('usage-running').textContent = runningInstances.length;
    document.getElementById('usage-pending').textContent = pendingInstances.length;
    document.getElementById('usage-expired').textContent = expiredInstances.length;
    document.getElementById('usage-total').textContent = instances.length;

    const runningList = document.getElementById('usage-running-list');
    const runningCount = document.getElementById('usage-running-list-count');
    runningCount.textContent = `${runningInstances.length} máy`;

    if (runningInstances.length === 0) {
        runningList.innerHTML = '<div class="usage-empty">Chưa có máy ảo đang chạy.</div>';
        return;
    }

    runningList.innerHTML = runningInstances.map(instance => `
        <div class="usage-instance-item">
            <div class="usage-instance-main">
                <div class="usage-instance-name">${escapeHtml(instance.packageName || 'CloudVM instance')}</div>
                <div class="usage-instance-meta">
                    ${escapeHtml(instance.instanceType || '-')} | ${instance.publicIp || 'Chưa có IP'}
                </div>
            </div>
            <span class="status-badge status-running">Đang chạy</span>
        </div>
    `).join('');
}

