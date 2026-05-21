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
        const isMainImage = item.closest('.section').querySelector('.section-title').textContent.startsWith('主图');
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
                    const isMainImage = item.closest('.section').querySelector('.section-title').textContent.startsWith('主图');
                    if (isMainImage) replaceWithPlaceholder(item); else item.remove();
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
    btn.textContent = '...'; btn.disabled = true;

    try {
        // Step 1: Backup original image BEFORE replacing (blocking)
        let originalBuffer = null;
        let originalType = null;
        if (!replaceIsPlaceholder && !imageBackups.has(replaceTargetPath)) {
            const backupUrl = toFileUrl(replaceTargetPath) + '?t=' + Date.now();
            console.log('备份原始图片: URL=', backupUrl);
            const backupResp = await fetch(backupUrl);
            console.log('备份响应:', backupResp.status, backupResp.headers.get('content-type'));
            if (!backupResp.ok) {
                throw new Error('备份原始图片失败: HTTP ' + backupResp.status);
            }
            originalBuffer = await backupResp.arrayBuffer();
            originalType = backupResp.headers.get('content-type') || 'image/jpeg';
            console.log('备份成功: buffer大小=', originalBuffer.byteLength, '类型=', originalType);
        } else if (imageBackups.has(replaceTargetPath)) {
            const entry = imageBackups.get(replaceTargetPath);
            originalBuffer = entry.buffer;
            originalType = entry.type;
            console.log('已有备份，大小=', originalBuffer.byteLength);
        }

        // Step 2: Fetch the selected replacement image
        const fetchUrl = replaceSelectedPath.split('?')[0] + '?t=' + Date.now();
        const imgResp = await fetch(fetchUrl);
        if (!imgResp.ok) throw new Error('图片获取失败: ' + imgResp.status);
        const imgBuffer = await imgResp.arrayBuffer();
        const imgFile = new File([imgBuffer], 'image.jpg', { type: imgResp.headers.get('content-type') || 'image/jpeg' });
        console.log('替换图片:', imgFile.name, imgFile.size, 'bytes, 类型:', imgFile.type);

        // Step 3: Send replacement request
        const endpoint = replaceIsPlaceholder ? '/api/files/create-image' : '/api/files/replace-image';
        const formData = new FormData();
        formData.append('targetPath', replaceTargetPath);
        formData.append('file', imgFile, 'image.jpg');
        console.log('发送到:', endpoint, '路径:', replaceTargetPath);
        const resp = await fetch(endpoint, { method: 'POST', body: formData });
        const data = await resp.json();
        console.log('替换响应:', resp.status, JSON.stringify(data));
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
                if (itemEl) { itemEl.setAttribute('data-filepath', replaceTargetPath); itemEl.innerHTML = itemHtml; }
                // Add new "+" placeholder for detail/SKU images
                addAddPlaceholder(itemEl);
                replaceIsPlaceholder = false; replacePlaceholderItem = null; replacePlaceholderName = null;
            } else {
                const item = btn.closest('.img-item');
                // Save backup with the ORIGINAL image (not the new one!)
                if (originalBuffer) {
                    imageBackups.set(replaceTargetPath, { buffer: originalBuffer, type: originalType });
                    console.log('保存原始备份: buffer大小=', originalBuffer.byteLength);
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
                }
            }
            closeReplaceModal();
        } else { showToast('替换失败: ' + (data.error || data.message), 'error'); }
    } catch (err) { showToast('请求失败: ' + err.message, 'error'); }
    finally { btn.textContent = '替换'; btn.disabled = false; }
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
            const newFileName = uploadTargetPath.split(/[\\/]/).pop();
            const newFileUrl = toFileUrl(uploadTargetPath) + '?t=' + Date.now();
            const wrapHtml = '<div class="img-wrap">' +
                '<img draggable="true" src="' + escapeHtml(newFileUrl) + '" onclick="showModal(this.src)" title="' + escapeHtml(newFileName) + '">' +
                '<div class="img-label">' + escapeHtml(newFileName) + '</div></div>';
            const actionsHtml = '<div class="img-actions-bar">' +
                '<button class="action-btn btn-replace" data-path="' + escapeAttr(uploadTargetPath) + '" onclick="replaceImageByAttr(this)">替换</button>' +
                '<button class="action-btn btn-delete" data-path="' + escapeAttr(uploadTargetPath) + '" onclick="deleteImageByAttr(this)">删除</button></div>';
            const itemEl = uploadPlaceholderEl.closest('.img-item');
            itemEl.setAttribute('data-filepath', uploadTargetPath);
            const oldBar = itemEl.querySelector('.img-actions-bar'); if (oldBar) oldBar.remove();
            uploadPlaceholderEl.outerHTML = wrapHtml + actionsHtml;
            // Fill any missing main image slots with placeholders
            fillMissingMainImageSlots(itemEl.closest('.product-card'));
            // Add new "+" placeholder for detail/SKU images
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
    // Check if "+" placeholder already exists
    const existingPlus = grid.querySelector('.img-placeholder .placeholder-icon');
    if (existingPlus && existingPlus.textContent === '+') return;

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

// Drag-and-drop reorder for detail images
let dragSrcItem = null;
let dragIndicator = null;

function setupImageDrag(card) {
    card.querySelectorAll('.image-grid').forEach(grid => {
        const section = grid.closest('.section');
        if (!section) return;
        const titleEl = section.querySelector('.section-title');
        if (!titleEl || (!titleEl.textContent.includes('主图') && !titleEl.textContent.includes('详情图'))) return;
        grid.querySelectorAll('.img-wrap img').forEach(img => {
            img.draggable = true;
            img.addEventListener('dragstart', handleDragStart);
            img.addEventListener('dragend', handleDragEnd);
        });
        grid.addEventListener('dragover', handleDragOver);
        grid.addEventListener('drop', function(e) { handleDrop(e, grid); });
    });
}

let dragGhost = null;

function handleDragStart(e) {
    dragSrcItem = this.closest('.img-item');
    if (!dragSrcItem) return;
    e.dataTransfer.effectAllowed = 'move';
    e.dataTransfer.setData('text/plain', '');

    // Hide native browser drag image (use a tiny transparent pixel)
    const blank = document.createElement('canvas');
    blank.width = 1; blank.height = 1;
    e.dataTransfer.setDragImage(blank, 0, 0);

    // Hide source but keep its space in the layout (flex won't reflow)
    dragSrcItem.style.transition = 'opacity 0.15s ease';
    dragSrcItem.style.opacity = '0.15';

    // Create a ghost clone - only the image, not the action bar
    const srcImg = dragSrcItem.querySelector('img');
    if (srcImg) {
        dragGhost = srcImg.cloneNode(true);
        dragGhost.style.position = 'fixed';
        dragGhost.style.left = '-9999px';
        dragGhost.style.width = '120px';
        dragGhost.style.height = '120px';
        dragGhost.style.objectFit = 'cover';
        dragGhost.style.zIndex = '9999';
        dragGhost.style.pointerEvents = 'none';
        dragGhost.style.opacity = '0.92';
        dragGhost.style.transform = 'rotate(3deg) scale(1.08)';
        dragGhost.style.boxShadow = '0 8px 30px rgba(255,106,0,0.4)';
        dragGhost.style.borderRadius = '10px';
        dragGhost.style.overflow = 'hidden';
        document.body.appendChild(dragGhost);

        // Position ghost at mouse
        dragGhost.style.left = (e.clientX - 60) + 'px';
        dragGhost.style.top = (e.clientY - 60) + 'px';
    }

    // Add transition to all other items for smooth slide animation
    const grid = dragSrcItem.closest('.image-grid');
    if (grid) {
        grid.querySelectorAll('.img-item').forEach(item => {
            if (item !== dragSrcItem) {
                item.style.transition = 'transform 0.25s ease';
            }
        });
    }
}

function handleDragEnd() {
    // Remove ghost
    if (dragGhost) { dragGhost.remove(); dragGhost = null; }
    // Restore shifted items
    if (lastShiftRow) {
        lastShiftRow.forEach(item => {
            item.style.transform = '';
            item.style.transition = '';
            item.style.opacity = '';
        });
        lastShiftRow = null;
        lastShiftIndex = -1;
    }
    // Restore source
    if (dragSrcItem) {
        dragSrcItem.style.opacity = '';
        dragSrcItem.style.transition = '';
        dragSrcItem.style.pointerEvents = '';
    }
    // Remove transitions and transforms from other items
    const grid = dragSrcItem?.closest('.image-grid');
    if (grid) {
        grid.querySelectorAll('.img-item').forEach(item => {
            item.style.transition = ''; item.style.transform = ''; item.style.opacity = '';
        });
    }
    removeDragIndicator(); dragSrcItem = null;
}

function handleDragOver(e) {
    e.preventDefault(); e.dataTransfer.dropEffect = 'move';

    // Move ghost to follow cursor
    if (dragGhost) {
        dragGhost.style.left = (e.clientX - 60) + 'px';
        dragGhost.style.top = (e.clientY - 60) + 'px';
    }

    const grid = e.currentTarget;
    const items = Array.from(grid.querySelectorAll('.img-item'));
    const visibleItems = items.filter(it => it !== dragSrcItem);
    if (visibleItems.length === 0) return;

    const mouseY = e.clientY;
    const mouseX = e.clientX;

    // Group items by visual row
    const rows = [];
    for (const item of visibleItems) {
        const rect = item.getBoundingClientRect();
        const cy = rect.top + rect.height / 2;
        let placed = false;
        for (const row of rows) {
            if (Math.abs(cy - row.y) < rect.height * 0.6) { row.items.push(item); placed = true; break; }
        }
        if (!placed) rows.push({ y: cy, items: [item] });
    }

    // Find the row the mouse is in
    let targetRow = rows[rows.length - 1];
    for (const row of rows) {
        const firstRect = row.items[0].getBoundingClientRect();
        const lastRect = row.items[row.items.length - 1].getBoundingClientRect();
        if (mouseY >= firstRect.top && mouseY <= lastRect.bottom) {
            targetRow = row;
            break;
        }
    }

    // Find insert position within the target row
    let insertIndex = items.length;
    for (let i = 0; i < targetRow.items.length; i++) {
        const item = targetRow.items[i];
        const rect = item.getBoundingClientRect();
        const centerX = rect.left + rect.width / 2;
        if (mouseX < centerX) {
            insertIndex = items.indexOf(item);
            break;
        }
    }

    // Shift animation: items from insertIndex onwards shift right
    applyShift(grid, items, insertIndex);

    showDragIndicator(grid, items, insertIndex);
}

let lastShiftRow = null;
let lastShiftIndex = -1;

function applyShift(grid, items, insertIndex) {
    // Skip if same as last applied
    if (insertIndex === lastShiftIndex && lastShiftRow) return;

    // Restore previous shifted items
    if (lastShiftRow) {
        lastShiftRow.forEach(item => {
            item.style.transition = 'none';
            item.style.transform = '';
            item.style.opacity = '';
        });
    }
    lastShiftRow = null;
    lastShiftIndex = -1;

    if (insertIndex < 0 || insertIndex >= items.length) return;

    // Find which row the insert index is in
    const visibleItems = items.filter(it => it !== dragSrcItem);
    const rows = [];
    for (const item of visibleItems) {
        const rect = item.getBoundingClientRect();
        const cy = rect.top + rect.height / 2;
        let placed = false;
        for (const row of rows) {
            if (Math.abs(cy - row.y) < rect.height * 0.6) { row.items.push(item); placed = true; break; }
        }
        if (!placed) rows.push({ y: cy, items: [item] });
    }

    // Find the row and shift items from insertIndex onwards
    for (const row of rows) {
        const rowItemIndices = row.items.map(it => items.indexOf(it));
        const minIdx = Math.min(...rowItemIndices);
        const maxIdx = Math.max(...rowItemIndices);

        if (insertIndex >= minIdx && insertIndex <= maxIdx + 1) {
            const shifted = [];
            for (const rowItem of row.items) {
                const itemIdx = items.indexOf(rowItem);
                if (itemIdx >= insertIndex) {
                    rowItem.style.transition = 'transform 0.2s cubic-bezier(0.4, 0, 0.2, 1)';
                    rowItem.style.transform = `translateX(${rowItem.offsetWidth + 12}px)`;
                    rowItem.style.opacity = '0.7';
                    shifted.push(rowItem);
                }
            }
            lastShiftRow = shifted;
            lastShiftIndex = insertIndex;
            return;
        }
    }
}

function showDragIndicator(grid, items, insertIndex) {
    removeDragIndicator();
    const visibleItems = items.filter(it => it !== dragSrcItem);
    dragIndicator = document.createElement('div');
    dragIndicator.className = 'drop-indicator';
    grid.style.position = 'relative';
    grid.appendChild(dragIndicator);
    const gridRect = grid.getBoundingClientRect();
    if (insertIndex >= visibleItems.length) {
        const last = visibleItems[visibleItems.length - 1];
        if (last) {
            const lastRect = last.getBoundingClientRect();
            dragIndicator.style.left = (lastRect.right - gridRect.left + 10) + 'px';
            dragIndicator.style.top = (lastRect.top - gridRect.top) + 'px';
            dragIndicator.style.height = lastRect.height + 'px';
        }
    } else {
        const target = visibleItems[insertIndex];
        if (target) {
            const targetRect = target.getBoundingClientRect();
            dragIndicator.style.left = (targetRect.left - gridRect.left - 4) + 'px';
            dragIndicator.style.top = (targetRect.top - gridRect.top) + 'px';
            dragIndicator.style.height = targetRect.height + 'px';
        }
    }
}

function removeDragIndicator() { if (dragIndicator) { dragIndicator.remove(); dragIndicator = null; } }

async function handleDrop(e, grid) {
    e.preventDefault(); removeDragIndicator();
    if (!dragSrcItem) return;
    const items = Array.from(grid.querySelectorAll('.img-item'));
    const visibleItems = items.filter(it => it !== dragSrcItem);

    const mouseY = e.clientY;
    const mouseX = e.clientX;

    // Group items by visual row
    const rows = [];
    for (const item of visibleItems) {
        const rect = item.getBoundingClientRect();
        const cy = rect.top + rect.height / 2;
        let placed = false;
        for (const row of rows) {
            if (Math.abs(cy - row.y) < rect.height * 0.6) { row.items.push(item); placed = true; break; }
        }
        if (!placed) rows.push({ y: cy, items: [item] });
    }

    // Find the row the mouse is in
    let targetRow = rows[rows.length - 1];
    for (const row of rows) {
        const firstRect = row.items[0].getBoundingClientRect();
        const lastRect = row.items[row.items.length - 1].getBoundingClientRect();
        if (mouseY >= firstRect.top && mouseY <= lastRect.bottom) {
            targetRow = row;
            break;
        }
    }

    // Find insert position within the target row
    let insertIndex = items.length;
    for (const item of targetRow.items) {
        if (mouseX < item.getBoundingClientRect().left + item.getBoundingClientRect().width / 2) {
            insertIndex = items.indexOf(item);
            break;
        }
    }
    // Restore shifted items before DOM reorder
    if (lastShiftRow) {
        lastShiftRow.forEach(item => {
            item.style.transform = '';
            item.style.transition = 'none';
            item.style.opacity = '';
        });
        lastShiftRow = null;
        lastShiftIndex = -1;
    }
    dragSrcItem.style.opacity = ''; dragSrcItem.style.transition = ''; dragSrcItem.style.pointerEvents = '';
    dragSrcItem.remove();
    const currentItems = grid.querySelectorAll('.img-item');
    if (insertIndex >= currentItems.length) grid.appendChild(dragSrcItem);
    else grid.insertBefore(dragSrcItem, currentItems[insertIndex]);
    dragSrcItem.style.transition = 'transform 0.3s ease, box-shadow 0.3s ease';
    dragSrcItem.style.boxShadow = '0 0 12px rgba(255,106,0,0.6)';
    setTimeout(() => { if (dragSrcItem) { dragSrcItem.style.boxShadow = ''; dragSrcItem.style.transition = ''; } }, 300);

    const section = grid.closest('.section');
    const productDir = section ? section.getAttribute('data-product-dir') : '';
    const titleText = section?.querySelector('.section-title')?.textContent.trim();
    const isMain = titleText?.startsWith('主图');

    // 主图：只交换文件路径，不重命名
    if (isMain) {
        const allItems = Array.from(grid.querySelectorAll('.img-item'));
        const srcPath = dragSrcItem.getAttribute('data-filepath');
        // insertIndex 是基于所有 items 的索引，但 dragSrcItem 已被 remove，需要修正
        // 实际上在 handleDrop 中 dragSrcItem 已被 remove 再 insert，所以 allItems 不包含 dragSrcItem
        // 需要重新获取
        const currentItems = Array.from(grid.querySelectorAll('.img-item'));
        // 找到 dragSrcItem 在 currentItems 中的位置
        const dragNewIndex = currentItems.indexOf(dragSrcItem);
        // 找到 insertIndex 位置的 item
        let targetItem = currentItems[insertIndex] || null;
        if (!targetItem || targetItem === dragSrcItem) {
            showToast('已重排序', 'success');
            return;
        }
        const targetPath = targetItem.getAttribute('data-filepath');
        if (!srcPath || !targetPath) {
            showToast('主图路径不完整，无法交换', 'error');
            return;
        }

        // 交换两个位置的 data-filepath、图片内容、label
        dragSrcItem.setAttribute('data-filepath', targetPath);
        targetItem.setAttribute('data-filepath', srcPath);
        dragSrcItem.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', targetPath));
        targetItem.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', srcPath));

        // 更新图片和 label
        const srcImg = dragSrcItem.querySelector('img');
        const targetImg = targetItem.querySelector('img');
        const srcLabel = dragSrcItem.querySelector('.img-label');
        const targetLabel = targetItem.querySelector('.img-label');
        const srcSrc = srcImg ? srcImg.src : '';
        const targetSrc = targetImg ? targetImg.src : '';
        const srcTitle = srcImg ? srcImg.title : '';
        const targetTitle = targetImg ? targetImg.title : '';

        if (srcImg && targetImg) {
            // 两个都有图：直接交换
            srcImg.src = targetSrc; targetImg.src = srcSrc;
            srcImg.title = targetTitle; targetImg.title = srcTitle;
            if (srcLabel) srcLabel.textContent = targetTitle;
            if (targetLabel) targetLabel.textContent = srcTitle;
        } else if (srcImg && !targetImg) {
            // src 有图，target 是 placeholder：把 src 的内容给 target，target 的内容给 src
            const targetPlaceholder = targetItem.querySelector('.img-placeholder');
            const targetPlaceholderText = targetPlaceholder ? targetPlaceholder.querySelector('.placeholder-text')?.textContent : '';
            // src 变成 placeholder
            const srcNum = srcPath.match(/主图_(\d{2})/)?.[1] || '01';
            replaceWithPlaceholderInner(dragSrcItem, srcPath, srcNum);
            // target 变成有图
            const targetWrap = targetItem.querySelector('.img-wrap');
            if (targetWrap) targetWrap.outerHTML = '<div class="img-wrap"><img draggable="true" src="' + escapeHtml(srcSrc) + '" onclick="showModal(this.src)" title="' + escapeHtml(srcTitle) + '"><div class="img-label">' + escapeHtml(srcTitle) + '</div></div>';
        } else if (!srcImg && targetImg) {
            // src 是 placeholder，target 有图
            const srcPlaceholder = dragSrcItem.querySelector('.img-placeholder');
            const srcPlaceholderText = srcPlaceholder ? srcPlaceholder.querySelector('.placeholder-text')?.textContent : '';
            // target 变成 placeholder
            const targetNum = targetPath.match(/主图_(\d{2})/)?.[1] || '01';
            replaceWithPlaceholderInner(targetItem, targetPath, targetNum);
            // src 变成有图
            const srcWrap = dragSrcItem.querySelector('.img-wrap');
            if (srcWrap) srcWrap.outerHTML = '<div class="img-wrap"><img draggable="true" src="' + escapeHtml(targetSrc) + '" onclick="showModal(this.src)" title="' + escapeHtml(targetTitle) + '"><div class="img-label">' + escapeHtml(targetTitle) + '</div></div>';
        }
        // 两个都是 placeholder：只需交换 data-filepath（上面已做）
        showToast('已重排序', 'success');
        return;
    }

    // 详情图/SKU图：原有逻辑
    if (!productDir) return;
    const orderedPaths = [];
    grid.querySelectorAll('.img-item[data-filepath]').forEach(item => { const fp = item.getAttribute('data-filepath'); if (fp) orderedPaths.push(fp); });
    try {
        await fetch('/api/files/reorder-detail-images', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, orderedPaths })
        });
        // Update DOM to reflect new filenames
        const isDetail = grid.closest('.section')?.querySelector('.section-title')?.textContent.trim().startsWith('详情图');
        const prefix = isDetail ? '详情图' : 'SKU图';
        const subDir = isDetail ? '详情图' : 'SKU图';
        let idx = 0;
        grid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
            const oldPath = item.getAttribute('data-filepath');
            const oldName = oldPath.split(/[\\/]/).pop();
            let ext = '';
            const dotIdx = oldName.lastIndexOf('.');
            if (dotIdx >= 0) ext = oldName.substring(dotIdx);
            const num = String(idx + 1).padStart(2, '0');
            const newName = prefix + '_' + num + ext;
            const newPath = productDir + '\\' + subDir + '\\' + newName;

            item.setAttribute('data-filepath', newPath);
            // Update button data-path attributes
            item.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', newPath));
            // Update label and title
            const labelEl = item.querySelector('.img-label');
            if (labelEl) labelEl.textContent = newName;
            const imgEl = item.querySelector('img');
            if (imgEl) {
                imgEl.title = newName;
                // Update src with cache-busting
                imgEl.src = toFileUrl(newPath) + '?t=' + Date.now();
            }
            idx++;
        });
        showToast('已重排序', 'success');
    } catch (err) { console.error('Failed to reorder images:', err); showToast('排序同步失败，请刷新', 'error'); }
}

// Clipboard paste
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
