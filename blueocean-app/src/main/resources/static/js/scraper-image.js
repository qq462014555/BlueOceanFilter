// scraper-image.js — 图片管理（删除、替换、复原、上传、拖拽、粘贴）

let replaceTargetPath = null;
let replaceTargetBtn = null;
let replaceSelectedPath = null;
let replaceIsPlaceholder = false;
let replacePlaceholderItem = null;
let replacePlaceholderName = null;
const imageBackups = new Map();

function replaceImageByAttr(btn) {
    const path = btn.getAttribute('data-path');
    replaceImage(btn, path, 0);
}

function restoreImageByAttr(btn) {
    const path = btn.getAttribute('data-path');
    restoreImage(btn, path);
}

function deleteImageByAttr(btn) {
    const path = btn.getAttribute('data-path');
    deleteImage(btn, path);
}

async function deleteImage(btn, filePath) {
    if (!filePath || filePath.startsWith('http://') || filePath.startsWith('https://') || filePath.startsWith('data:')) {
        const item = btn.closest('.img-item');
        const section = item.closest('.section');
        const titleEl = section.querySelector('.section-title');
        const isMainImage = titleEl.textContent.startsWith('主图');
        if (isMainImage) replaceWithPlaceholder(item);
        else item.remove();
        return;
    }
    showConfirm('确定删除这张图片？', 'danger', function(ok) {
        if (!ok) return;
        (async function() {
            try {
                const encoded = encodeURIComponent(filePath);
                const resp = await fetch('/api/files/view?path=' + encodeURIComponent(filePath), { method: 'DELETE' });
                const contentType = resp.headers.get('content-type');
                let data;
                if (contentType && contentType.includes('application/json')) data = await resp.json();
                else { const text = await resp.text(); data = resp.ok ? { message: text } : { error: text }; }
                if (resp.ok) {
                    const item = btn.closest('.img-item');
                    const section = item.closest('.section');
                    const titleEl = section.querySelector('.section-title');
                    const isMainImage = titleEl.textContent.startsWith('主图');
                    const isDetail = titleEl.textContent.startsWith('详情图');
                    const isSkuImage = titleEl.textContent.startsWith('SKU图');
                    if (isMainImage) {
                        replaceWithPlaceholder(item);
                    } else if (isSkuImage) {
                        const grid = item.closest('.image-grid');
                        const card = item.closest('.product-card');
                        replaceSkuWithPlaceholder(item);
                        skuImageRefreshTable(card, grid);
                    } else {
                        if (isDetail) {
                            await reloadDetailImages(section);
                        } else {
                            item.remove();
                        }
                    }
                    showToast('已删除', 'success');
                } else { showToast('删除失败: ' + (data.error || data.message), 'error'); }
            } catch (e) { showToast('请求失败: ' + e.message, 'error'); }
        })();
    });
}

function replaceWithPlaceholder(itemEl) {
    const grid = itemEl.closest('.image-grid');
    const allItems = grid.querySelectorAll('.img-item');
    const index = Array.from(allItems).indexOf(itemEl);
    const placeholderName = '主图_' + String(index + 1).padStart(2, '0') + '.jpg';
    const card = itemEl.closest('.product-card');
    const dirEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    const productDir = dirEl ? dirEl.getAttribute('data-product-dir') || '' : '';
    const placeholderPath = productDir + '\\主图\\' + placeholderName;

    const placeholderHtml = '<div class="img-item" data-filepath="' + escapeAttr(placeholderPath) + '">' +
        '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(placeholderPath) + '\', \'主图_' + String(index + 1).padStart(2, '0') + '\')">' +
        '<div class="placeholder-icon">+</div>' +
        '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>' +
        '</div>' +
        '<div class="img-actions-bar">' +
        '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replacePlaceholder(this)">替换</button>' +
        '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>' +
        '</div></div>';
    itemEl.outerHTML = placeholderHtml;
}

/**
 * 将 img-item 替换为 placeholder（内部使用，保持原有 data-filepath 和序号）
 */
function replaceWithPlaceholderInner(itemEl, filePath, numStr) {
    const placeholderName = '主图_' + numStr + '.jpg';
    const placeholderHtml = '<div class="img-item" data-filepath="' + escapeAttr(filePath) + '">' +
        '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(filePath) + '\', \'主图_' + numStr + '\')">' +
        '<div class="placeholder-icon">+</div>' +
        '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>' +
        '</div>' +
        '<div class="img-actions-bar">' +
        '<button class="action-btn btn-replace" data-path="' + escapeAttr(filePath) + '" onclick="replacePlaceholder(this)">替换</button>' +
        '<button class="action-btn btn-delete" data-path="' + escapeAttr(filePath) + '" onclick="deleteImageByAttr(this)">删除</button>' +
        '</div></div>';
    itemEl.outerHTML = placeholderHtml;
}

/**
 * SKU 图删除后变占位框（不重编号，保留空位）
 */
function replaceSkuWithPlaceholder(itemEl) {
    const grid = itemEl.closest('.image-grid');
    const allItems = grid.querySelectorAll('.img-item');
    const index = Array.from(allItems).indexOf(itemEl);
    const numStr = String(index + 1).padStart(2, '0');
    const placeholderName = 'SKU图_' + numStr + '.jpg';
    const card = itemEl.closest('.product-card');
    const dirEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    const productDir = dirEl ? dirEl.getAttribute('data-product-dir') || '' : '';
    const placeholderPath = productDir + '\\SKU图\\' + placeholderName;

    const placeholderHtml = '<div class="img-item" data-filepath="' + escapeAttr(placeholderPath) + '">' +
        '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(placeholderPath) + '\', \'SKU图_' + numStr + '\')">' +
        '<div class="placeholder-icon">+</div>' +
        '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>' +
        '</div>' +
        '<div class="img-actions-bar">' +
        '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replacePlaceholder(this)">替换</button>' +
        '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>' +
        '</div></div>';
    itemEl.outerHTML = placeholderHtml;
}

function replaceImage(btn, filePath, index) {
    if (!filePath || filePath.startsWith('http://') || filePath.startsWith('https://') || filePath.startsWith('data:')) {
        showToast('远程图片不支持替换', 'error'); return;
    }
    replaceTargetPath = filePath;
    replaceTargetBtn = btn;
    replaceSelectedPath = null;

    const card = btn.closest('.product-card');
    if (!card) { showToast('无法定位商品卡片', 'error'); return; }
    const images = card.querySelectorAll('.image-grid img');
    if (images.length === 0) { showToast('没有可用的图片', 'error'); return; }

    const body = document.getElementById('replaceModalBody');
    body.innerHTML = '';

    const sections = {};
    images.forEach(img => {
        const wrap = img.closest('.img-wrap');
        if (!wrap) return;
        const label = wrap.querySelector('.img-label');
        const sectionName = label ? label.textContent : '其他';
        let sectionKey = '其他';
        if (sectionName.startsWith('主图')) sectionKey = '主图';
        else if (sectionName.startsWith('详情图')) sectionKey = '详情图';
        else if (sectionName.startsWith('SKU')) sectionKey = 'SKU图';
        if (!sections[sectionKey]) sections[sectionKey] = [];
        if (img.src) sections[sectionKey].push(img);
    });

    for (const [sectionKey, imgs] of Object.entries(sections)) {
        const groupDiv = document.createElement('div');
        groupDiv.className = 'section-group';
        groupDiv.innerHTML = '<div class="section-group-title">' + escapeHtml(sectionKey) + '</div>';
        const grid = document.createElement('div');
        grid.className = 'replace-grid';
        imgs.forEach(img => {
            const src = img.src;
            const opt = document.createElement('div');
            opt.className = 'replace-option';
            opt.innerHTML = '<img src="' + src + '" alt="">';
            opt.onclick = function() {
                if (src.split('?')[0] === toFileUrl(replaceTargetPath)) return;
                grid.querySelectorAll('.replace-option').forEach(o => o.classList.remove('selected'));
                opt.classList.add('selected');
                replaceSelectedPath = src;
                document.getElementById('replaceConfirmBtn').disabled = false;
            };
            grid.appendChild(opt);
        });
        groupDiv.appendChild(grid);
        body.appendChild(groupDiv);
    }

    document.getElementById('replaceModalTitle').textContent = '选择图片替换: ' + filePath.split(/[\\/]/).pop();
    document.getElementById('replaceConfirmBtn').disabled = true;
    document.getElementById('replaceModal').classList.add('active');
}

function closeReplaceModal() {
    document.getElementById('replaceModal').classList.remove('active');
    replaceTargetPath = null; replaceTargetBtn = null; replaceSelectedPath = null;
    replaceIsPlaceholder = false; replacePlaceholderItem = null; replacePlaceholderName = null;
}

async function confirmReplace() {
    if (!replaceTargetPath || !replaceSelectedPath) return;

    const btn = replaceTargetBtn;
    if (!btn) return;
    try { btn.textContent = '...'; btn.disabled = true; } catch(e) {}

    try {
        let originalBuffer = null;
        let originalType = null;
        if (!replaceIsPlaceholder && !imageBackups.has(replaceTargetPath)) {
            const backupUrl = toFileUrl(replaceTargetPath) + '?t=' + Date.now();
            const backupResp = await fetch(backupUrl);
            if (!backupResp.ok) {
                throw new Error('备份原始图片失败: HTTP ' + backupResp.status);
            }
            originalBuffer = await backupResp.arrayBuffer();
            originalType = backupResp.headers.get('content-type') || 'image/jpeg';
        } else if (imageBackups.has(replaceTargetPath)) {
            const entry = imageBackups.get(replaceTargetPath);
            originalBuffer = entry.buffer;
            originalType = entry.type;
        }

        const fetchUrl = replaceSelectedPath.split('?')[0] + '?t=' + Date.now();
        const imgResp = await fetch(fetchUrl);
        if (!imgResp.ok) throw new Error('图片获取失败: ' + imgResp.status);
        const imgBuffer = await imgResp.arrayBuffer();
        const imgFile = new File([imgBuffer], 'image.jpg', { type: imgResp.headers.get('content-type') || 'image/jpeg' });

        const endpoint = replaceIsPlaceholder ? '/api/files/create-image' : '/api/files/replace-image';
        const formData = new FormData();
        formData.append('targetPath', replaceTargetPath);
        formData.append('file', imgFile, 'image.jpg');
        const resp = await fetch(endpoint, { method: 'POST', body: formData });
        const data = await resp.json();
        if (resp.ok) {
            if (replaceIsPlaceholder) {
                const itemEl = replacePlaceholderItem;
                const fileName = replaceTargetPath.split(/[\\/]/).pop();
                const itemHtml = '<div class="img-wrap">' +
                    '<img draggable="true" src="' + escapeHtml(replaceSelectedPath) + '" onclick="showModal(this.src)" title="' + escapeHtml(fileName) + '">' +
                    '<div class="img-label">' + escapeHtml(fileName) + '</div>' +
                    '</div>' +
                    '<div class="img-actions-bar">' +
                    '<button class="action-btn btn-replace" data-path="' + escapeAttr(replaceTargetPath) + '" onclick="replaceImageByAttr(this)">替换</button>' +
                    '<button class="action-btn btn-delete" data-path="' + escapeAttr(replaceTargetPath) + '" onclick="deleteImageByAttr(this)">删除</button>' +
                    '</div>';
                if (itemEl) {
                    itemEl.setAttribute('data-filepath', replaceTargetPath);
                    itemEl.innerHTML = itemHtml;
                    addAddPlaceholder(itemEl);
                    // Refresh SKU table if this is a SKU image
                    if (replaceTargetPath.indexOf('SKU图') >= 0) {
                        const placeholderRef = replacePlaceholderItem;
                        setTimeout(() => {
                            const itemEl2 = placeholderRef ? placeholderRef.closest('.img-item') : null;
                            if (!itemEl2) return;
                            const grid = itemEl2.closest('.image-grid');
                            const card = itemEl2.closest('.product-card');
                            if (grid && card) skuImageRefreshTable(card, grid);
                        }, 50);
                    }
                }
                replaceIsPlaceholder = false; replacePlaceholderItem = null; replacePlaceholderName = null;
            } else {
                const item = btn.closest('.img-item');
                if (originalBuffer) {
                    imageBackups.set(replaceTargetPath, { buffer: originalBuffer, type: originalType });
                }
                if (item) {
                    const restoreBtn = item.querySelector('.restore-btn');
                    if (restoreBtn) {
                        restoreBtn.classList.add('visible');
                        restoreBtn.style.display = 'inline-block';
                    }
                    const imgEl = item.querySelector('img');
                    if (imgEl) {
                        imgEl.src = imgEl.src.split('?')[0] + '?t=' + Date.now();
                    }
                    // Refresh SKU table if this is a SKU image
                    if (replaceTargetPath.indexOf('SKU图') >= 0) {
                        const grid = item.closest('.image-grid');
                        const card = item.closest('.product-card');
                        if (grid && card) skuImageRefreshTable(card, grid);
                    }
                }
            }
            closeReplaceModal();
        } else { showToast('替换失败: ' + (data.error || data.message), 'error'); }
    } catch (err) { showToast('请求失败: ' + err.message, 'error'); }
    finally {
        if (btn) { try { btn.textContent = '替换'; btn.disabled = false; } catch(e) {} }
    }
}

async function openProductDir(dirPath) {
    try {
        const resp = await fetch('/api/files/open-dir', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dirPath: dirPath })
        });
        const data = await resp.json();
        if (!resp.ok) showToast('打开目录失败: ' + (data.error || data.message), 'error');
    } catch (e) { showToast('请求失败: ' + e.message, 'error'); }
}

async function restoreImage(btn, targetPath) {
    const backupEntry = imageBackups.get(targetPath);
    if (!backupEntry) { showToast('没有原始图片备份', 'error'); return; }
    const { buffer, type } = backupEntry;
    console.log('复原: targetPath=', targetPath, 'buffer大小=', buffer.byteLength, '类型=', type);
    btn.textContent = '...'; btn.disabled = true;
    try {
        const file = new File([buffer], 'image.jpg', { type: type });
        console.log('创建 File: name=', file.name, 'size=', file.size, 'type=', file.type);
        const formData = new FormData();
        formData.append('targetPath', targetPath);
        formData.append('file', file, 'image.jpg');
        const resp = await fetch('/api/files/replace-image', { method: 'POST', body: formData });
        const data = await resp.json();
        console.log('复原响应:', resp.status, JSON.stringify(data));
        if (resp.ok) {
            imageBackups.delete(targetPath);
            btn.classList.remove('visible');
            const item = btn.closest('.img-item');
            if (item) {
                const imgEl = item.querySelector('img');
                if (imgEl) {
                    imgEl.src = imgEl.src.split('?')[0] + '?t=' + Date.now();
                }
            }
            showToast('已复原', 'success');
        } else { showToast('复原失败: ' + (await resp.json()).error, 'error'); }
    } catch (err) { showToast('请求失败: ' + err.message, 'error'); }
    finally { btn.textContent = '复原'; btn.disabled = false; }
}

function replacePlaceholder(btn) {
    const itemEl = btn.closest('.img-item');
    if (!itemEl) { showToast('无法定位图片占位', 'error'); return; }
    const path = btn.getAttribute('data-path');
    const textEl = itemEl.querySelector('.placeholder-text');
    const placeholderName = textEl ? textEl.textContent : path.split(/[\\/]/).pop();
    replaceIsPlaceholder = true;
    replacePlaceholderItem = itemEl;
    replacePlaceholderName = placeholderName;
    replaceImage(btn, path, 0);
}

// Upload modal
let uploadPlaceholderEl = null;
let uploadTargetPath = null;
let uploadTargetName = null;
let selectedFileBlob = null;
let selectedTabImageSrc = null;
let currentProductCardForUpload = null;

function switchUploadTab(tabName) {
    document.querySelectorAll('.upload-modal-tab').forEach(t => t.classList.remove('active'));
    document.querySelector(`.upload-modal-tab[data-tab="${tabName}"]`).classList.add('active');
    document.querySelectorAll('.upload-modal-body .tab-content').forEach(c => c.classList.remove('active'));
    if (tabName === 'local') document.getElementById('tabLocal').classList.add('active');
    else if (tabName === 'main') { document.getElementById('tabMain').classList.add('active'); populateMainImageGrid(); }
    else if (tabName === 'detail') { document.getElementById('tabDetail').classList.add('active'); populateDetailImageGrid(); }
    selectedTabImageSrc = null;
    document.getElementById('uploadConfirmBtn').disabled = false;
}

function populateMainImageGrid() {
    const grid = document.getElementById('mainImageGrid');
    grid.innerHTML = '';
    if (!currentProductCardForUpload) { grid.innerHTML = '<div class="empty-hint">未找到商品信息</div>'; return; }
    const mainImages = [];
    currentProductCardForUpload.querySelectorAll('.image-grid').forEach(g => {
        const section = g.closest('.section');
        if (section) {
            const title = section.querySelector('.section-title');
            if (title && title.textContent.trim().startsWith('主图')) g.querySelectorAll('img').forEach(img => { if (img.src) mainImages.push(img.src); });
        }
    });
    if (mainImages.length === 0) { grid.innerHTML = '<div class="empty-hint">该商品没有主图</div>'; return; }
    mainImages.forEach(src => {
        const item = document.createElement('div');
        item.className = 'image-select-item';
        item.innerHTML = '<img src="' + src + '" alt="">';
        item.onclick = function() {
            grid.querySelectorAll('.image-select-item').forEach(o => o.classList.remove('selected'));
            item.classList.add('selected'); selectedTabImageSrc = src;
        };
        grid.appendChild(item);
    });
}

function populateDetailImageGrid() {
    const grid = document.getElementById('detailImageGrid');
    grid.innerHTML = '';
    if (!currentProductCardForUpload) { grid.innerHTML = '<div class="empty-hint">未找到商品信息</div>'; return; }
    const detailImages = [];
    currentProductCardForUpload.querySelectorAll('.image-grid').forEach(g => {
        const section = g.closest('.section');
        if (section) {
            const title = section.querySelector('.section-title');
            if (title && title.textContent.trim().startsWith('详情图')) g.querySelectorAll('img').forEach(img => { if (img.src) detailImages.push(img.src); });
        }
    });
    if (detailImages.length === 0) { grid.innerHTML = '<div class="empty-hint">该商品没有详情图</div>'; return; }
    detailImages.forEach(src => {
        const item = document.createElement('div');
        item.className = 'image-select-item';
        item.innerHTML = '<img src="' + src + '" alt="">';
        item.onclick = function() {
            grid.querySelectorAll('.image-select-item').forEach(o => o.classList.remove('selected'));
            item.classList.add('selected'); selectedTabImageSrc = src;
        };
        grid.appendChild(item);
    });
}

function openUploadModal(placeholderEl, targetPath, targetName) {
    uploadPlaceholderEl = placeholderEl;
    uploadTargetPath = targetPath;
    uploadTargetName = targetName;
    selectedFileBlob = null; selectedTabImageSrc = null;
    currentProductCardForUpload = placeholderEl.closest('.product-card');
    document.getElementById('uploadModalTitle').textContent = '上传: ' + targetName;
    document.getElementById('uploadFileInput').value = '';
    document.getElementById('uploadUrlInput').value = '';
    document.getElementById('uploadConfirmBtn').disabled = false;
    switchUploadTab('local');
    document.getElementById('uploadModal').classList.add('active');
}

function closeUploadModal() {
    document.getElementById('uploadModal').classList.remove('active');
    uploadPlaceholderEl = null; uploadTargetPath = null; uploadTargetName = null;
    selectedFileBlob = null; selectedTabImageSrc = null; currentProductCardForUpload = null;
}

function onFileSelected(input) {
    const file = input.files[0];
    if (!file) { selectedFileBlob = null; return; }
    selectedFileBlob = file;
}

async function confirmUpload() {
    if (!uploadTargetPath || !uploadPlaceholderEl) return;
    let fileBlob = selectedFileBlob;

    // If tab image selected, fetch it as blob
    if (!fileBlob && selectedTabImageSrc) {
        try {
            const resp = await fetch(selectedTabImageSrc);
            fileBlob = await resp.blob();
        } catch (e) { showToast('图片获取失败: ' + e.message, 'error'); return; }
    }

    // If no file and no tab image, try URL
    if (!fileBlob) {
        const url = document.getElementById('uploadUrlInput').value.trim();
        if (!url) { showToast('请选择文件或填写图片URL', 'error'); return; }
        try {
            const resp = await fetch(url); fileBlob = await resp.blob();
        } catch (e) { showToast('图片URL无效: ' + e.message, 'error'); return; }
    }

    const btn = document.getElementById('uploadConfirmBtn');
    btn.textContent = '上传中...'; btn.disabled = true;
    try {
        const formData = new FormData();
        formData.append('targetPath', uploadTargetPath);
        formData.append('file', fileBlob, 'image.jpg');
        const resp = await fetch('/api/files/create-image', { method: 'POST', body: formData });
        if (resp.ok) {
            // Save backup for the newly uploaded image
            if (!imageBackups.has(uploadTargetPath)) {
                const uploadBuffer = await fileBlob.arrayBuffer();
                imageBackups.set(uploadTargetPath, { buffer: uploadBuffer, type: fileBlob.type || 'image/jpeg' });
            }
            // 详情图：直接 reload 后端最新数据
            if (uploadTargetPath.indexOf('详情图') >= 0) {
                const section = uploadPlaceholderEl.closest('.section');
                if (section) await reloadDetailImages(section);
                showToast('已上传', 'success'); closeUploadModal();
                return;
            }
            // SKU 图：更新 DOM + 刷新 table
            if (uploadTargetPath.indexOf('SKU图') >= 0) {
                const respPath = data.path || uploadTargetPath;
                const newFileName = respPath.split(/[\\/]/).pop();
                const newFileUrl = toFileUrl(respPath) + '?t=' + Date.now();
                const itemEl = uploadPlaceholderEl.closest('.img-item');
                itemEl.setAttribute('data-filepath', respPath);
                itemEl.innerHTML = '<div class="img-wrap">' +
                    '<img draggable="true" src="' + escapeHtml(newFileUrl) + '" onclick="showModal(this.src)" title="' + escapeHtml(newFileName) + '">' +
                    '<div class="img-label">' + escapeHtml(newFileName) + '</div></div>' +
                    '<div class="img-actions-bar">' +
                    '<button class="action-btn btn-replace" data-path="' + escapeAttr(respPath) + '" onclick="replaceImageByAttr(this)">替换</button>' +
                    '<button class="action-btn btn-delete" data-path="' + escapeAttr(respPath) + '" onclick="deleteImageByAttr(this)">删除</button></div>';
                fillMissingMainImageSlots(itemEl.closest('.product-card'));
                addAddPlaceholder(itemEl);
                const grid = itemEl.closest('.image-grid');
                const card = itemEl.closest('.product-card');
                setTimeout(() => { if (grid && card) skuImageRefreshTable(card, grid); }, 50);
                showToast('已上传', 'success'); closeUploadModal();
                return;
            }
            // 主图等其他类型
            const respPath = data.path || uploadTargetPath;
            const newFileName = respPath.split(/[\\/]/).pop();
            const newFileUrl = toFileUrl(respPath) + '?t=' + Date.now();
            const itemEl = uploadPlaceholderEl.closest('.img-item');
            itemEl.setAttribute('data-filepath', respPath);
            itemEl.innerHTML = '<div class="img-wrap">' +
                '<img draggable="true" src="' + escapeHtml(newFileUrl) + '" onclick="showModal(this.src)" title="' + escapeHtml(newFileName) + '">' +
                '<div class="img-label">' + escapeHtml(newFileName) + '</div></div>' +
                '<div class="img-actions-bar">' +
                '<button class="action-btn btn-replace" data-path="' + escapeAttr(respPath) + '" onclick="replaceImageByAttr(this)">替换</button>' +
                '<button class="action-btn btn-delete" data-path="' + escapeAttr(respPath) + '" onclick="deleteImageByAttr(this)">删除</button></div>';
            fillMissingMainImageSlots(itemEl.closest('.product-card'));
            addAddPlaceholder(itemEl);
            showToast('已上传', 'success'); closeUploadModal();
        } else { showToast('上传失败: ' + (await resp.json()).error, 'error'); }
    } catch (e) { showToast('请求失败: ' + e.message, 'error'); }
    finally { btn.textContent = '确认上传'; btn.disabled = false; }
}

function fillMissingMainImageSlots(card) {
    if (!card) return;
    const mainGrid = Array.from(card.querySelectorAll('.image-grid')).find(g => {
        const s = g.closest('.section');
        return s && s.querySelector('.section-title') && s.querySelector('.section-title').textContent.trim().startsWith('主图');
    });
    if (!mainGrid) return;

    const section = mainGrid.closest('.section');
    const productDir = section ? section.getAttribute('data-product-dir') : '';
    if (!productDir) return;

    const existingPaths = new Set();
    let maxNum = 5; // default 5 slots
    mainGrid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
        const fp = item.getAttribute('data-filepath');
        existingPaths.add(fp);
        const m = fp.match(/主图_(\d{2})/);
        if (m) maxNum = Math.max(maxNum, parseInt(m[1]));
    });

    // Fill any gaps
    for (let i = 1; i <= maxNum; i++) {
        const num = String(i).padStart(2, '0');
        const expectedPath = productDir + '\\主图\\主图_' + num + '.jpg';
        if (existingPaths.has(expectedPath)) continue;

        const placeholderName = '主图_' + num + '.jpg';
        const placeholderHtml = '<div class="img-item" data-filepath="' + escapeAttr(expectedPath) + '">' +
            '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(expectedPath) + '\', \'主图_' + num + '\')">' +
            '<div class="placeholder-icon">+</div>' +
            '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>' +
            '</div>' +
            '<div class="img-actions-bar">' +
            '<button class="action-btn btn-replace" data-path="' + escapeAttr(expectedPath) + '" onclick="replacePlaceholder(this)">替换</button>' +
            '<button class="action-btn btn-delete" data-path="' + escapeAttr(expectedPath) + '" onclick="deleteImageByAttr(this)">删除</button>' +
            '</div></div>';

        // Insert in correct position (sorted by path)
        let inserted = false;
        for (const item of Array.from(mainGrid.querySelectorAll('.img-item'))) {
            const itemPath = item.getAttribute('data-filepath') || '';
            if (itemPath > expectedPath) {
                item.insertAdjacentHTML('beforebegin', placeholderHtml);
                inserted = true;
                break;
            }
        }
        if (!inserted) {
            mainGrid.insertAdjacentHTML('beforeend', placeholderHtml);
        }
    }
}

function addAddPlaceholder(itemEl) {
    const grid = itemEl.closest('.image-grid');
    if (!grid) return;
    const section = grid.closest('.section');
    if (!section) return;
    const title = section.querySelector('.section-title');
    if (!title) return;
    // Only for detail and SKU images
    const titleText = title.textContent.trim();
    if (!titleText.startsWith('详情图') && !titleText.startsWith('SKU')) return;
    // Check if the last item is already a "+" placeholder
    const lastItem = grid.lastElementChild;
    if (lastItem) {
        const lastPlus = lastItem.querySelector('.placeholder-icon');
        if (lastPlus && lastPlus.textContent === '+') return;
    }

    const productDir = section.getAttribute('data-product-dir') || '';
    const isDetail = titleText.startsWith('详情图');
    const subDirName = isDetail ? '详情图' : 'SKU图';
    const prefix = isDetail ? '详情图' : 'SKU图';
    const existingCount = grid.querySelectorAll('.img-item[data-filepath]').length;
    const num = String(existingCount + 1).padStart(2, '0');
    const fileName = prefix + '_' + num + '.jpg';
    const targetPath = productDir + '\\' + subDirName + '\\' + fileName;

    const plusHtml = '<div class="img-item">' +
        '<div class="img-placeholder" onclick="' + (isDetail ? 'addDetailImage(this)' : 'addSkuImage(this)') + '">' +
        '<div class="placeholder-icon">+</div>' +
        '<div class="placeholder-text">添加图</div>' +
        '</div></div>';
    grid.insertAdjacentHTML('beforeend', plusHtml);
}

function addDetailImage(placeholderEl) {
    const section = placeholderEl.closest('.section');
    const productDir = section ? section.getAttribute('data-product-dir') : '';
    if (!productDir) { showToast('没有产品目录信息', 'error'); return; }
    const detailDir = productDir + '\\详情图';
    const grid = placeholderEl.closest('.image-grid');
    const existingCount = grid ? grid.querySelectorAll('.img-item[data-filepath]').length : 0;
    const num = String(existingCount + 1).padStart(2, '0');
    const fileName = '详情图_' + num + '.jpg';
    openUploadModal(placeholderEl, detailDir + '\\' + fileName, fileName);
}

function addSkuImage(placeholderEl) {
    const section = placeholderEl.closest('.section');
    const productDir = section ? section.getAttribute('data-product-dir') : '';
    if (!productDir) { showToast('没有产品目录信息', 'error'); return; }
    const skuDir = productDir + '\\SKU图';
    const grid = placeholderEl.closest('.image-grid');
    const existingCount = grid ? grid.querySelectorAll('.img-item[data-filepath]').length : 0;
    const num = String(existingCount + 1).padStart(2, '0');
    const fileName = 'SKU图_' + num + '.jpg';
    openUploadModal(placeholderEl, skuDir + '\\' + fileName, fileName);
}

// ==================== Batch Select & Delete ====================

function toggleDetailCheck(wrap, event) {
    const item = wrap.closest('.img-item');
    const cb = item.querySelector('.img-checkbox');
    cb.checked = !cb.checked;
    onImgCheck(cb);
}

function toggleBatchCheckAll(masterCheckbox, sectionType) {
    const section = masterCheckbox.closest('.section');
    const checks = section.querySelectorAll('.img-checkbox[data-section="' + sectionType + '"]');
    checks.forEach(cb => {
        cb.checked = masterCheckbox.checked;
        const item = cb.closest('.img-item');
        if (cb.checked) item.classList.add('selected');
        else item.classList.remove('selected');
    });
    updateBatchCount(sectionType);
}

function onImgCheck(cb) {
    const item = cb.closest('.img-item');
    if (cb.checked) item.classList.add('selected');
    else item.classList.remove('selected');

    // Update "select all" checkbox state
    const section = cb.closest('.section');
    const masterCb = section.querySelector('input[type="checkbox"][id^="batchCheckAll"]');
    if (masterCb) {
        const allChecks = section.querySelectorAll('.img-checkbox[data-section]');
        const checkedCount = section.querySelectorAll('.img-checkbox:checked').length;
        masterCb.checked = allChecks.length === checkedCount && checkedCount > 0;
    }
    updateBatchCount(cb.getAttribute('data-section'));
}

function updateBatchCount(sectionType) {
    const section = document.querySelector('.detail-section');
    if (!section) return;
    const countEl = section.querySelector('.batch-count');
    const barEl = section.querySelector('.batch-bar');
    if (!countEl || !barEl) return;
    const checked = section.querySelectorAll('.img-checkbox:checked').length;
    countEl.textContent = '已选 ' + checked;
    if (checked > 0) barEl.classList.add('active');
    else barEl.classList.remove('active');
}

async function reloadDetailImages(section) {
    const productDir = section.getAttribute('data-product-dir') || '';
    if (!productDir) return;

    const resp = await fetch('/api/files/list-images', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ productDir })
    });
    if (!resp.ok) return;
    const data = await resp.json();
    const images = data.detailImages || [];
    const paths = images.map(img => img.path);

    let html = '';
    paths.forEach((path) => {
        const fileName = path ? path.split(/[\\/]/).pop() : '';
        html += '<div class="img-item" data-filepath="' + escapeAttr(path) + '">';
        html += '<input type="checkbox" class="img-checkbox" data-section="detail" onclick="event.stopPropagation()" onchange="onImgCheck(this)" title="选中">';
        html += '<div class="img-wrap" onclick="toggleDetailCheck(this, event)" ondblclick="showModal(this.querySelector(\'img\').src)">';
        html += '<img draggable="true" src="' + escapeHtml(path ? toFileUrl(path) : '') + '?t=' + Date.now() + '" title="' + escapeHtml(fileName) + '">';
        html += '<div class="img-label">' + escapeHtml(fileName) + '</div>';
        html += '</div>';
        html += '<div class="img-actions-bar">';
        html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(path) + '" onclick="replaceImageByAttr(this)">替换</button>';
        html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(path) + '" onclick="restoreImageByAttr(this)">复原</button>';
        html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(path) + '" onclick="deleteImageByAttr(this)">删除</button>';
        html += '</div></div>';
    });
    html += '<div class="img-item">';
    html += '<div class="img-placeholder" onclick="addDetailImage(this)">';
    html += '<div class="placeholder-icon">+</div>';
    html += '<div class="placeholder-text">添加图</div>';
    html += '</div></div>';

    const grid = section.querySelector('.image-grid');
    if (grid) {
        // 销毁旧的 SortableJS 实例前先从 Map 移除（防止 onEnd 误触发）
        if (typeof _detailSortableMap !== 'undefined') {
            const existing = _detailSortableMap.get(grid);
            if (existing) {
                _detailSortableMap.delete(grid);
                existing.destroy();
            }
        }

        grid.innerHTML = html;

        // 重新初始化 SortableJS
        if (typeof setupDetailDrag === 'function') {
            setupDetailDrag(section.closest('.product-card'));
        }
    }

    const badge = section.querySelector('.section-title .badge');
    if (badge) badge.textContent = paths.length;

    updateBatchCount('detail');
}

async function batchDeleteSelected(sectionType) {
    const checked = document.querySelectorAll('.img-checkbox:checked[data-section="' + sectionType + '"]');
    if (checked.length === 0) { showToast('没有选中图片', 'error'); return; }

    const count = checked.length;
    showConfirm('确定删除选中的 ' + count + ' 张图片？', 'danger', async function(ok) {
        if (!ok) return;

        // Collect paths and remove items from DOM
        const sectionsMap = new Map(); // section -> paths[]
        checked.forEach(cb => {
            const item = cb.closest('.img-item');
            const section = item.closest('.section');
            const path = item.getAttribute('data-filepath');
            if (!sectionsMap.has(section)) sectionsMap.set(section, []);
            if (path && !path.startsWith('http://') && !path.startsWith('https://')) {
                sectionsMap.get(section).push(path);
            }
            item.remove();
        });

        // Update UI
        updateBatchCount(sectionType);
        const section = document.querySelector('.detail-section');
        const masterCb = section ? section.querySelector('input[type="checkbox"][id^="batchCheckAll"]') : null;
        if (masterCb) masterCb.checked = false;

        // Delete files and renumber
        let successCount = 0;
        let failCount = 0;
        for (const [section, paths] of sectionsMap) {
            for (const path of paths) {
                try {
                    const resp = await fetch('/api/files/view?path=' + encodeURIComponent(path), { method: 'DELETE' });
                    if (resp.ok) successCount++;
                    else failCount++;
                } catch (e) { failCount++; }
            }
            if (sectionType === 'detail') await reloadDetailImages(section);
        }

        if (failCount === 0) showToast('已删除 ' + successCount + ' 张图片', 'success');
        else showToast('已删除 ' + successCount + ' 张，失败 ' + failCount + ' 张', 'error');
    });
}

async function renumberDetailImages(section) {
    const prefix = '详情图';
    const productDir = section.getAttribute('data-product-dir') || '';
    if (!productDir) return;

    const items = section.querySelectorAll('.img-item[data-filepath]');
    items.forEach((item, idx) => {
        const num = String(idx + 1).padStart(2, '0');
        const fileName = prefix + '_' + num + '.jpg';
        const newPath = productDir + '\\' + prefix + '\\' + fileName;
        item.setAttribute('data-filepath', newPath);
        const cb = item.querySelector('.img-checkbox');
        if (cb) { cb.checked = false; item.classList.remove('selected'); }
        item.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', newPath));
        const labelEl = item.querySelector('.img-label');
        if (labelEl) labelEl.textContent = fileName;
        const imgEl = item.querySelector('img');
        if (imgEl) { imgEl.title = fileName; imgEl.src = toFileUrl(newPath) + '?t=' + Date.now(); }
    });

    // Reorder on disk
    const orderedPaths = [];
    items.forEach(item => orderedPaths.push(item.getAttribute('data-filepath')));
    if (orderedPaths.length > 0) {
        try {
            await fetch('/api/files/reorder-detail-images', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ productDir, orderedPaths })
            });
        } catch (e) { /* ignore */ }
    }

    const badge = section.querySelector('.section-title .badge');
    if (badge) badge.textContent = items.length;
}
window.addEventListener('paste', function(e) {
    const modal = document.getElementById('uploadModal');
    if (!modal.classList.contains('active')) return;
    const items = e.clipboardData && e.clipboardData.items;
    if (!items) return;
    for (let i = 0; i < items.length; i++) {
        if (items[i].type.indexOf('image') !== -1) {
            selectedFileBlob = items[i].getAsFile();
            switchUploadTab('local');
            document.getElementById('uploadModalTitle').textContent = '已粘贴图片 ✓';
            setTimeout(() => { document.getElementById('uploadModalTitle').textContent = '上传: ' + (uploadTargetName || ''); }, 1500);
            e.preventDefault();
            break;
        }
    }
});

// ==================== Video Upload / Replace / Delete / Restore ====================

let videoUploadTargetPath = null;
let videoUploadPlaceholderEl = null;
let videoSelectedFileBlob = null;
const videoBackups = new Map();

function openVideoUploadModal(placeholderEl, targetPath) {
    videoUploadPlaceholderEl = placeholderEl;
    videoUploadTargetPath = targetPath;
    videoSelectedFileBlob = null;
    document.getElementById('videoUploadModalTitle').textContent = '上传视频';
    document.getElementById('videoFileInput').value = '';
    document.getElementById('videoUrlInput').value = '';
    document.getElementById('videoUploadConfirmBtn').disabled = false;
    document.getElementById('videoUploadModal').classList.add('active');
}

function closeVideoUploadModal() {
    document.getElementById('videoUploadModal').classList.remove('active');
    videoUploadPlaceholderEl = null;
    videoUploadTargetPath = null;
    videoSelectedFileBlob = null;
}

function onVideoFileSelected(input) {
    const file = input.files[0];
    if (!file) { videoSelectedFileBlob = null; return; }
    videoSelectedFileBlob = file;
}

async function confirmVideoUpload() {
    if (!videoUploadTargetPath) return;
    let fileBlob = videoSelectedFileBlob;

    // If no file, try URL
    if (!fileBlob) {
        const url = document.getElementById('videoUrlInput').value.trim();
        if (!url) { showToast('请选择视频文件或填写视频URL', 'error'); return; }
        try {
            showToast('正在下载视频...');
            const resp = await fetch(url);
            if (!resp.ok) throw new Error('HTTP ' + resp.status);
            fileBlob = await resp.blob();
        } catch (e) { showToast('视频URL无效: ' + e.message, 'error'); return; }
    }

    // Validate it's a video
    if (!fileBlob.type.startsWith('video/')) {
        showToast('请选择视频文件（mp4/webm）', 'error');
        return;
    }

    const btn = document.getElementById('videoUploadConfirmBtn');
    btn.textContent = '上传中...'; btn.disabled = true;
    try {
        const formData = new FormData();
        formData.append('targetPath', videoUploadTargetPath);
        formData.append('file', fileBlob, 'video.mp4');
        const resp = await fetch('/api/files/create-video', { method: 'POST', body: formData });
        if (resp.ok) {
            // Save backup
            if (!videoBackups.has(videoUploadTargetPath)) {
                const uploadBuffer = await fileBlob.arrayBuffer();
                videoBackups.set(videoUploadTargetPath, { buffer: uploadBuffer, type: fileBlob.type || 'video/mp4' });
            }
            // Find the video-item from the current DOM
            const itemEl = findVideoItemByPath(videoUploadTargetPath);
            const newFileName = videoUploadTargetPath.split(/[\\/]/).pop();
            const newFileUrl = toFileUrl(videoUploadTargetPath) + '?t=' + Date.now();

            const videoHtml = '<div class="video-wrap">' +
                '<video style="max-width:100%;max-height:300px;border-radius:8px;" controls preload="metadata"><source src="' + escapeHtml(newFileUrl) + '" type="video/mp4"></video>' +
                '<div class="video-label">' + escapeHtml(newFileName) + '</div></div>' +
                '<div class="video-actions-bar">' +
                '<button class="action-btn btn-replace" data-path="' + escapeAttr(videoUploadTargetPath) + '" onclick="replaceVideo(this)">替换</button>' +
                '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(videoUploadTargetPath) + '" onclick="restoreVideo(this)" style="display:none">复原</button>' +
                '<button class="action-btn btn-delete" data-path="' + escapeAttr(videoUploadTargetPath) + '" onclick="deleteVideo(this)">删除</button>';

            if (itemEl) {
                itemEl.setAttribute('data-filepath', videoUploadTargetPath);
                itemEl.innerHTML = videoHtml;
            }
            showToast('视频已上传', 'success');
            closeVideoUploadModal();
        } else {
            const data = await resp.json().catch(() => ({ error: '上传失败' }));
            showToast('上传失败: ' + (data.error || data.message), 'error');
        }
    } catch (e) { showToast('请求失败: ' + e.message, 'error'); }
    finally { btn.textContent = '确认上传'; btn.disabled = false; }
}

function findVideoItemByPath(targetPath) {
    // Search all .video-item elements for matching data-filepath
    const items = document.querySelectorAll('.video-item[data-filepath]');
    for (const item of items) {
        if (item.getAttribute('data-filepath') === targetPath) return item;
    }
    // Fallback: find by the modal's closest context
    if (videoUploadPlaceholderEl) {
        return videoUploadPlaceholderEl.closest('.video-item');
    }
    return null;
}

function replaceVideo(btn) {
    const path = btn.getAttribute('data-path');
    videoUploadTargetPath = path;
    // Find the video-item and open modal
    const itemEl = btn.closest('.video-item');
    if (!itemEl) { showToast('无法定位视频', 'error'); return; }
    videoUploadPlaceholderEl = itemEl.querySelector('.video-wrap') || itemEl;
    document.getElementById('videoUploadModalTitle').textContent = '替换视频';
    document.getElementById('videoFileInput').value = '';
    document.getElementById('videoUrlInput').value = '';
    document.getElementById('videoUploadConfirmBtn').disabled = false;
    document.getElementById('videoUploadModal').classList.add('active');
}

function replaceVideoPlaceholder(btn) {
    const itemEl = btn.closest('.video-item');
    videoUploadPlaceholderEl = itemEl.querySelector('.video-placeholder') || itemEl;
    replaceVideo(btn);
}

async function deleteVideo(btn) {
    const path = btn.getAttribute('data-path');
    if (!path || path.startsWith('http://') || path.startsWith('https://')) {
        // Just remove the video element, show placeholder
        const itemEl = btn.closest('.video-item');
        const section = itemEl.closest('.section');
        const productDir = section ? section.getAttribute('data-product-dir') || '' : '';
        const placeholderPath = productDir + '\\视频\\视频.mp4';
        const placeholderHtml = '<div class="video-placeholder" onclick="openVideoUploadModal(this, \'' + escapeJs(placeholderPath) + '\')">' +
            '<div class="placeholder-icon">+</div>' +
            '<div class="placeholder-text">添加视频</div></div>' +
            '<div class="video-actions-bar">' +
            '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replaceVideoPlaceholder(this)">替换</button>' +
            '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteVideo(this)">删除</button></div>';
        itemEl.setAttribute('data-filepath', placeholderPath);
        itemEl.innerHTML = placeholderHtml;
        return;
    }
    showConfirm('确定删除这个视频？', 'danger', function(ok) {
        if (!ok) return;
        (async function() {
            try {
                const resp = await fetch('/api/files/view?path=' + encodeURIComponent(path), { method: 'DELETE' });
                const contentType = resp.headers.get('content-type');
                let data;
                if (contentType && contentType.includes('application/json')) data = await resp.json();
                else { const text = await resp.text(); data = resp.ok ? { message: text } : { error: text }; }
                if (resp.ok) {
                    const itemEl = btn.closest('.video-item');
                    const section = itemEl.closest('.section');
                    const productDir = section ? section.getAttribute('data-product-dir') || '' : '';
                    const placeholderPath = productDir + '\\视频\\视频.mp4';
                    const placeholderHtml = '<div class="video-placeholder" onclick="openVideoUploadModal(this, \'' + escapeJs(placeholderPath) + '\')">' +
                        '<div class="placeholder-icon">+</div>' +
                        '<div class="placeholder-text">添加视频</div></div>' +
                        '<div class="video-actions-bar">' +
                        '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replaceVideoPlaceholder(this)">替换</button>' +
                        '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteVideo(this)">删除</button></div>';
                    itemEl.setAttribute('data-filepath', placeholderPath);
                    itemEl.innerHTML = placeholderHtml;
                    showToast('视频已删除', 'success');
                } else {
                    showToast('删除失败: ' + (data.error || data.message), 'error');
                }
            } catch (e) { showToast('请求失败: ' + e.message, 'error'); }
        })();
    });
}

async function restoreVideo(btn) {
    const path = btn.getAttribute('data-path');
    const backupEntry = videoBackups.get(path);
    if (!backupEntry) { showToast('没有原始视频备份', 'error'); return; }
    const { buffer, type } = backupEntry;
    btn.textContent = '...'; btn.disabled = true;
    try {
        const file = new File([buffer], 'video.mp4', { type: type });
        const formData = new FormData();
        formData.append('targetPath', path);
        formData.append('file', file, 'video.mp4');
        const resp = await fetch('/api/files/replace-video', { method: 'POST', body: formData });
        const data = await resp.json();
        if (resp.ok) {
            videoBackups.delete(path);
            btn.classList.remove('visible');
            const itemEl = btn.closest('.video-item');
            if (itemEl) {
                const videoEl = itemEl.querySelector('video source');
                if (videoEl) {
                    videoEl.src = videoEl.src.split('?')[0] + '?t=' + Date.now();
                    // Reload the video element
                    const video = itemEl.querySelector('video');
                    if (video) video.load();
                }
            }
            showToast('视频已复原', 'success');
        } else {
            showToast('复原失败: ' + (data.error || data.message), 'error');
        }
    } catch (err) { showToast('请求失败: ' + err.message, 'error'); }
    finally { btn.textContent = '复原'; btn.disabled = false; }
}

// ==================== SKU Row Drag Reorder (SortableJS) ====================

/**
 * 缓存每个 tbody 的 Sortable 实例，用于销毁重建
 */
const _skuSortableMap = new Map();

function setupSkuDrag(card) {
    const tbodies = card.querySelectorAll('.sku-table-draggable tbody');
    tbodies.forEach(tbody => {
        // 销毁旧实例
        const existing = _skuSortableMap.get(tbody);
        if (existing) { existing.destroy(); _skuSortableMap.delete(tbody); }

        _skuSortableMap.set(tbody, new Sortable(tbody, {
            animation: 200,
            ghostClass: 'sortable-ghost',
            chosenClass: 'sortable-chosen',
            dragClass: 'sortable-drag',
            onEnd: function(evt) {
                if (evt.newIndex === evt.oldIndex) return;
                submitSkuReorder(tbody);
            }
        }));
    });
}

async function submitSkuReorder(tbody) {
    const table = tbody.closest('.sku-table');
    const section = table.closest('.section');
    const card = section.closest('.product-card');
    const titleEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    let productDir = titleEl ? titleEl.getAttribute('data-product-dir') || '' : '';
    if (!productDir) return;

    const rows = tbody.querySelectorAll('.sku-row');
    const orderedSpecNames = [];
    rows.forEach(row => {
        const specCell = row.querySelector('.sku-spec-name-cell');
        orderedSpecNames.push(specCell ? specCell.textContent.trim() : '');
    });

    try {
        const resp = await fetch('/api/files/reorder-skus', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, orderedSpecNames })
        });
        const respData = await resp.json();
        if (!resp.ok || respData.error) {
            showToast('SKU重排失败: ' + (respData.error || ''), 'error');
            return;
        }
        showToast('SKU顺序已更新', 'success');
        const skuSection = Array.from(card.querySelectorAll('.section')).find(s => {
            const t = s.querySelector('.section-title');
            return t && t.textContent.trim().startsWith('SKU图');
        });
        if (skuSection) skuSection.querySelector('.image-grid').querySelectorAll('img').forEach(img => img.src = img.src.split('?')[0] + '?t=' + Date.now());
    } catch (e) {
        showToast('SKU重排请求失败: ' + e.message, 'error');
    }
}

/**
 * 删除 SKU 行，同步到商品数据.json、价格表.csv、商品属性.json
 */
async function deleteSkuRow(btn) {
    const specName = btn.getAttribute('data-spec-name');
    if (!specName) return;

    showConfirm('确定删除 SKU「' + specName + '」？', 'danger', async function(ok) {
        if (!ok) return;

        const row = btn.closest('.sku-row');
        const card = row.closest('.product-card');
        const titleEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
        const productDir = titleEl ? titleEl.getAttribute('data-product-dir') || '' : '';
        if (!productDir) { showToast('无法定位商品目录', 'error'); return; }

        try {
            const resp = await fetch('/api/files/delete-sku', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ productDir, specName })
            });
            const data = await resp.json();
            if (!resp.ok || data.error) {
                showToast('删除失败: ' + (data.error || data.message), 'error');
                return;
            }
            row.remove();

            // 更新 badge
            card.querySelectorAll('.section').forEach(s => {
                const t = s.querySelector('.section-title');
                if (t && t.textContent.trim().startsWith('SKU 信息')) {
                    const badge = t.querySelector('.badge');
                    if (badge) badge.textContent = parseInt(badge.textContent) - 1;
                }
            });

            // 刷新 SKU 图区域（从后端重新获取图片列表）
            const skuImgSection = Array.from(card.querySelectorAll('.section')).find(s => {
                const t = s.querySelector('.section-title');
                return t && t.textContent.trim().startsWith('SKU图');
            });
            if (skuImgSection) {
                const productDir = skuImgSection.getAttribute('data-product-dir') || '';
                const listResp = await fetch('/api/files/list-images', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ productDir })
                });
                if (listResp.ok) {
                    const listData = await listResp.json();
                    const skuImages = listData.skuImages || [];
                    const paths = skuImages.map(img => img.path);
                    const totalSlots = paths.length;

                    const slotMap = {};
                    paths.forEach(path => {
                        const fileName = path.split(/[\\/]/).pop();
                        const match = fileName.match(/SKU图_(\d+)/);
                        if (match) slotMap[parseInt(match[1], 10)] = path;
                    });

                    let html = '';
                    for (let si = 1; si <= totalSlots; si++) {
                        const siFile = slotMap[si] || null;
                        if (siFile) {
                            const siName = siFile.split(/[\\/]/).pop();
                            html += '<div class="img-item" data-filepath="' + escapeAttr(siFile) + '">';
                            html += '<div class="img-wrap">';
                            html += '<img draggable="true" src="' + escapeHtml(toFileUrl(siFile)) + '?t=' + Date.now() + '" onclick="showModal(this.src)" title="' + escapeHtml(siName) + '">';
                            html += '<div class="img-label">' + escapeHtml(siName) + '</div>';
                            html += '</div>';
                            html += '<div class="img-actions-bar">';
                            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(siFile) + '" onclick="replaceImageByAttr(this)">替换</button>';
                            html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(siFile) + '" onclick="restoreImageByAttr(this)">复原</button>';
                            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(siFile) + '" onclick="deleteImageByAttr(this)">删除</button>';
                            html += '</div></div>';
                        } else {
                            const placeholderName = 'SKU图_' + String(si).padStart(2, '0') + '.jpg';
                            const placeholderPath = productDir + '\\SKU图\\' + placeholderName;
                            html += '<div class="img-item" data-filepath="' + escapeAttr(placeholderPath) + '">';
                            html += '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(placeholderPath) + '\', \'SKU图_' + String(si).padStart(2, '0') + '\')">';
                            html += '<div class="placeholder-icon">+</div>';
                            html += '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>';
                            html += '</div>';
                            html += '<div class="img-actions-bar">';
                            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replacePlaceholder(this)">替换</button>';
                            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>';
                            html += '</div></div>';
                        }
                    }

                    const grid = skuImgSection.querySelector('.image-grid');
                    if (grid) {
                        if (_skuSortableMap.has(grid)) { _skuSortableMap.get(grid).destroy(); _skuSortableMap.delete(grid); }
                        grid.innerHTML = html;
                        if (typeof setupSkuImageDrag === 'function') setupSkuImageDrag(card);
                        const badge = skuImgSection.querySelector('.section-title .badge');
                        if (badge) badge.textContent = totalSlots;
                    }
                }
            }

            showToast('已删除', 'success');
        } catch (e) {
            showToast('删除请求失败: ' + e.message, 'error');
        }
    });
}

/**
 * 从后端重新加载 SKU 图区域
 */
async function reloadSkuImages(section, skuCount) {
    const productDir = section.getAttribute('data-product-dir') || '';
    if (!productDir) return;

    const resp = await fetch('/api/files/list-images', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ productDir })
    });
    if (!resp.ok) return;
    const data = await resp.json();
    const skuImages = data.skuImages || [];

    // Map file paths by slot number from filename
    const slotMap = {};
    skuImages.forEach(img => {
        const path = img.path || '';
        const fileName = path.split(/[\\/]/).pop();
        const match = fileName.match(/SKU图_(\d+)/);
        if (match) slotMap[parseInt(match[1], 10)] = path;
    });

    const totalSlots = Math.max(skuCount, skuImages.length);

    let html = '';
    for (let si = 1; si <= totalSlots; si++) {
        const siFile = slotMap[si] || null;
        if (siFile) {
            const siName = siFile.split(/[\\/]/).pop();
            html += '<div class="img-item" data-filepath="' + escapeAttr(siFile) + '">';
            html += '<div class="img-wrap">';
            html += '<img draggable="true" src="' + escapeHtml(toFileUrl(siFile)) + '?t=' + Date.now() + '" onclick="showModal(this.src)" title="' + escapeHtml(siName) + '">';
            html += '<div class="img-label">' + escapeHtml(siName) + '</div>';
            html += '</div>';
            html += '<div class="img-actions-bar">';
            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(siFile) + '" onclick="replaceImageByAttr(this)">替换</button>';
            html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(siFile) + '" onclick="restoreImageByAttr(this)">复原</button>';
            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(siFile) + '" onclick="deleteImageByAttr(this)">删除</button>';
            html += '</div></div>';
        } else {
            const placeholderName = 'SKU图_' + String(si).padStart(2, '0') + '.jpg';
            const placeholderPath = productDir + '\\SKU图\\' + placeholderName;
            html += '<div class="img-item" data-filepath="' + escapeAttr(placeholderPath) + '">';
            html += '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(placeholderPath) + '\', \'SKU图_' + String(si).padStart(2, '0') + '\')">';
            html += '<div class="placeholder-icon">+</div>';
            html += '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>';
            html += '</div>';
            html += '<div class="img-actions-bar">';
            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replacePlaceholder(this)">替换</button>';
            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>';
            html += '</div></div>';
        }
    }

    const grid = section.querySelector('.image-grid');
    if (grid) {
        if (_skuSortableMap.has(grid)) {
            _skuSortableMap.get(grid).destroy();
            _skuSortableMap.delete(grid);
        }
        grid.innerHTML = html;
        if (typeof setupSkuImageDrag === 'function') setupSkuImageDrag(section.closest('.product-card'));
    }

    const badge = section.querySelector('.section-title .badge');
    if (badge) badge.textContent = totalSlots;
}
