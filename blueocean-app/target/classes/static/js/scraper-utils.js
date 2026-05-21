// scraper-utils.js — 通用工具函数

function toFileUrl(filePath) {
    if (!filePath) return '';
    if (filePath.startsWith('http://') || filePath.startsWith('https://') || filePath.startsWith('data:') || filePath.startsWith('/rpa-files/')) return filePath;
    // Strip base dir prefix for Tomcat static serving
    const normalized = filePath.replace(/\\/g, '/');
    const baseDir = 'C:/Users/46201/Documents/无极RPA文件处理/';
    const relativePath = normalized.startsWith(baseDir) ? normalized.substring(baseDir.length) : normalized;
    return '/rpa-files/' + relativePath;
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function escapeAttr(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function unescapeAttr(str) {
    if (!str) return '';
    return str.replace(/&quot;/g, '"').replace(/&amp;/g, '&');
}

function escapeJs(str) {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '\\"');
}

let toastTimer = null;
function showToast(message, type) {
    if (toastTimer) clearTimeout(toastTimer);
    document.querySelectorAll('.toast').forEach(t => t.remove());
    const el = document.createElement('div');
    el.className = 'toast ' + (type || 'info');
    el.textContent = message;
    document.body.appendChild(el);
    requestAnimationFrame(() => { requestAnimationFrame(() => { el.classList.add('show'); }); });
    toastTimer = setTimeout(() => {
        el.classList.remove('show');
        setTimeout(() => el.remove(), 300);
    }, 2000);
}

let confirmCallback = null;
function showConfirm(message, type, callback) {
    const overlay = document.getElementById('confirmOverlay');
    const icon = document.getElementById('confirmIcon');
    const msg = document.getElementById('confirmMessage');
    const okBtn = document.getElementById('confirmOkBtn');
    icon.textContent = type === 'danger' ? '!' : '?';
    icon.className = 'confirm-icon ' + (type || 'warning');
    msg.textContent = message;
    okBtn.className = 'confirm-btn primary';
    if (type === 'danger') {
        okBtn.style.background = '#ff4d4f'; okBtn.style.borderColor = '#ff4d4f';
    } else {
        okBtn.style.background = '#ff6a00'; okBtn.style.borderColor = '#ff6a00';
    }
    confirmCallback = callback;
    overlay.classList.add('show');
}

function confirmOk() {
    document.getElementById('confirmOverlay').classList.remove('show');
    if (confirmCallback) confirmCallback(true);
    confirmCallback = null;
}

function confirmCancel() {
    document.getElementById('confirmOverlay').classList.remove('show');
    if (confirmCallback) confirmCallback(false);
    confirmCallback = null;
}
