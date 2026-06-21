// scraper-product.js — 商品卡片渲染

function renderProduct(product) {
    const container = document.getElementById('results');
    let html = buildProductCardHtml(product, true);
    const productId = 'product-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5);
    html = html.replace('<div class="product-card">', '<div class="product-card" id="' + productId + '">');
    container.insertAdjacentHTML('afterbegin', html);
    addSidebarItem(product.dirTitle || product.title || '未知商品', productId);
    const card = document.getElementById(productId);
    if (card) { setupMainImageDrag(card); setupDetailDrag(card); setupSkuImageDrag(card); setupSkuDrag(card); }
}

function renderLoadedProduct(product) {
    const container = document.getElementById('results');
    let html = buildProductCardHtml(product, false);
    const productId = 'product-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5);
    html = html.replace('<div class="product-card">', '<div class="product-card" id="' + productId + '">');
    container.insertAdjacentHTML('afterbegin', html);
    addSidebarItem(product.dirTitle || product.title || '未知商品', productId);
    const card = document.getElementById(productId);
    if (card) { setupMainImageDrag(card); setupDetailDrag(card); setupSkuImageDrag(card); setupSkuDrag(card); }
}

function buildProductCardHtml(product, isFromScrape) {
    let html = '<div class="product-card">';

    // Product info
    html += '<div class="product-info">';
    html += '<div class="product-title-row">';
    html += '<div class="label">' + (isFromScrape ? '采集标题' : '采集标题') + '</div>';
    html += '<div class="title-value">' + escapeHtml(isFromScrape ? (product.title || '未知商品') : (product.scrapedTitle || '未知商品')) + '</div>';
    html += '</div>';
    html += '<div class="product-title-row">';
    html += '<div class="label">目录标题</div>';
    html += '<div class="product-title" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
    html += '<span class="title-text" ondblclick="startEditTitleFromText(this)">' + escapeHtml(product.dirTitle || product.title || '未知商品') + '</span>';
    const _dirTitle = product.dirTitle || product.title || '未知商品';
    const _byteCount = countBytes(_dirTitle);
    const _byteColor = _byteCount > DIR_TITLE_MAX_BYTES ? '#ff4d4f' : (_byteCount > DIR_TITLE_MAX_BYTES * 0.8 ? '#fa8c16' : '#52c41a');
    html += '<span class="title-byte-count-static" style="font-size:12px;color:' + _byteColor + ';white-space:nowrap;margin-left:8px;">' + _byteCount + '/' + DIR_TITLE_MAX_BYTES + ' 字节</span>';
    html += '<button class="edit-btn" onclick="startEditTitle(this)">编辑</button>';
    html += '</div></div>';
    html += '<div class="product-meta">';
    if (product.categoryPath) html += '<span>类目: ' + escapeHtml(product.categoryPath) + '</span>';
    if (product.layout) html += '<span>布局: ' + escapeHtml(product.layout) + '</span>';
    html += '<span class="dir-link" onclick="openProductDir(\'' + escapeJs(product.productDir || '') + '\')" title="点击打开目录">📁 打开目录</span>';
    html += '</div></div>';

    // Main images — always 5 slots
    html += '<div class="section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
    html += '<div class="section-title">主图 <span class="badge">' + (product.mainImages ? product.mainImages.length : 0) + '/5</span></div>';
    html += '<div class="image-grid">';
    var mainImgCount = product.mainImages ? product.mainImages.length : 0;
    for (var mi = 0; mi < 5; mi++) {
        var miFile = mi < mainImgCount ? product.mainImages[mi] : null;
        if (miFile) {
            var miName = miFile.split(/[\\/]/).pop();
            html += '<div class="img-item" data-filepath="' + escapeAttr(miFile) + '">';
            html += '<div class="img-wrap">';
            html += '<img draggable="true" src="' + escapeHtml(toFileUrl(miFile)) + '?t=' + Date.now() + '" onclick="showModal(this.src)" title="' + escapeHtml(miName) + '">';
            html += '<div class="img-label">' + escapeHtml(miName) + '</div>';
            html += '</div>';
            html += '<div class="img-actions-bar">';
            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(miFile) + '" onclick="replaceImageByAttr(this)">替换</button>';
            html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(miFile) + '" onclick="restoreImageByAttr(this)">复原</button>';
            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(miFile) + '" onclick="deleteImageByAttr(this)">删除</button>';
            html += '</div></div>';
        } else {
            var placeholderName = '主图_' + String(mi + 1).padStart(2, '0') + '.jpg';
            var placeholderPath = (product.productDir || '') + '\\主图\\' + placeholderName;
            html += '<div class="img-item" data-filepath="' + escapeAttr(placeholderPath) + '">';
            html += '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(placeholderPath) + '\', \'主图_' + String(mi + 1).padStart(2, '0') + '\')">';
            html += '<div class="placeholder-icon">+</div>';
            html += '<div class="placeholder-text">' + escapeHtml(placeholderName) + '</div>';
            html += '</div>';
            html += '<div class="img-actions-bar">';
            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(placeholderPath) + '" onclick="replacePlaceholder(this)">替换</button>';
            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(placeholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>';
            html += '</div></div>';
        }
    }
    html += '</div></div>';

    // Detail images
    if (product.detailImages && product.detailImages.length > 0) {
        html += '<div class="section detail-section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
        html += '<div class="section-title">详情图 <span class="badge">' + product.detailImages.length + '</span>';
        html += '<span class="batch-bar" id="batchBar-detail">';
        html += '<input type="checkbox" id="batchCheckAllDetail" onchange="toggleBatchCheckAll(this, \'detail\')" title="全选">';
        html += '<span class="batch-count" id="batchCountDetail">已选 0</span>';
        html += '<button class="batch-btn danger" onclick="batchDeleteSelected(\'detail\')">删除选中</button>';
        html += '</span></div>';
        html += '<div class="image-grid">';
        product.detailImages.forEach((img) => {
            const fileName = img ? img.split(/[\\/]/).pop() : '';
            html += '<div class="img-item" data-filepath="' + escapeAttr(img) + '">';
            html += '<input type="checkbox" class="img-checkbox" data-section="detail" onclick="event.stopPropagation()" onchange="onImgCheck(this)" title="选中">';
            html += '<div class="img-wrap" onclick="toggleDetailCheck(this, event)" ondblclick="showModal(this.querySelector(\'img\').src)">';
            html += '<img draggable="true" src="' + escapeHtml(img ? toFileUrl(img) : '') + '?t=' + Date.now() + '" title="' + escapeHtml(fileName) + '">';
            html += '<div class="img-label">' + escapeHtml(fileName) + '</div>';
            html += '</div>';
            html += '<div class="img-actions-bar">';
            html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(img) + '" onclick="replaceImageByAttr(this)">替换</button>';
            html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(img) + '" onclick="restoreImageByAttr(this)">复原</button>';
            html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(img) + '" onclick="deleteImageByAttr(this)">删除</button>';
            html += '</div></div>';
        });
        html += '<div class="img-item">';
        html += '<div class="img-placeholder" onclick="addDetailImage(this)">';
        html += '<div class="placeholder-icon">+</div>';
        html += '<div class="placeholder-text">添加图</div>';
        html += '</div></div>';
        html += '</div></div>';
    }

    // Video
    if (product.videoUrl) {
        const videoFileName = product.videoUrl.split(/[\\/]/).pop();
        html += '<div class="section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
        html += '<div class="section-title">商品视频</div>';
        html += '<div class="video-item" data-filepath="' + escapeAttr(product.videoUrl) + '">';
        html += '<div class="video-wrap">';
        const videoSrc = isFromScrape
            ? (product.videoUrl.startsWith('http') || product.videoUrl.startsWith('//') || product.videoUrl.startsWith('data:')
                ? (product.videoUrl.startsWith('//') ? 'https:' + product.videoUrl : product.videoUrl)
                : toFileUrl(product.videoUrl) + '?t=' + Date.now())
            : toFileUrl(product.videoUrl) + '?t=' + Date.now();
        html += '<video style="max-width:100%;max-height:300px;border-radius:8px;" controls preload="metadata"><source src="' + escapeHtml(videoSrc) + '" type="video/mp4"></video>';
        html += '<div class="video-label">' + escapeHtml(videoFileName) + '</div>';
        html += '</div>';
        html += '<div class="video-actions-bar">';
        html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(product.videoUrl) + '" onclick="replaceVideo(this)">替换</button>';
        html += '<button class="action-btn restore-btn btn-restore" data-path="' + escapeAttr(product.videoUrl) + '" onclick="restoreVideo(this)" style="display:none">复原</button>';
        html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(product.videoUrl) + '" onclick="deleteVideo(this)">删除</button>';
        html += '</div></div></div>';
    } else {
        // No video - show placeholder upload box
        const videoPath = (product.productDir || '') + '\\视频\\视频.mp4';
        html += '<div class="section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
        html += '<div class="section-title">商品视频</div>';
        html += '<div class="video-item" data-filepath="' + escapeAttr(videoPath) + '">';
        html += '<div class="video-placeholder" onclick="openVideoUploadModal(this, \'' + escapeJs(videoPath) + '\')">';
        html += '<div class="placeholder-icon">+</div>';
        html += '<div class="placeholder-text">添加视频</div>';
        html += '</div>';
        html += '<div class="video-actions-bar">';
        html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(videoPath) + '" onclick="replaceVideoPlaceholder(this)">替换</button>';
        html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(videoPath) + '" onclick="deleteVideo(this)">删除</button>';
        html += '</div></div></div>';
    }

    // Attributes
    if (product.attributes && Object.keys(product.attributes).length > 0) {
        html += '<div class="section">';
        html += '<div class="section-title">商品属性 <span class="badge">' + Object.keys(product.attributes).length + '</span></div>';
        html += '<table class="sku-table"><tbody>';
        const keys = Object.keys(product.attributes);
        for (let i = 0; i < keys.length; i += 2) {
            html += '<tr>';
            html += '<th style="background:#fafafa;width:80px;white-space:nowrap">' + escapeHtml(keys[i]) + '</th>';
            html += '<td>' + escapeHtml(product.attributes[keys[i]]) + '</td>';
            if (i + 1 < keys.length) {
                html += '<th style="background:#fafafa;width:80px;white-space:nowrap">' + escapeHtml(keys[i + 1]) + '</th>';
                html += '<td>' + escapeHtml(product.attributes[keys[i + 1]]) + '</td>';
            } else { html += '<th style="background:#fafafa">-</th><td>-</td>'; }
            html += '</tr>';
        }
        html += '</tbody></table></div>';
    }

    // Pack info
    if (product.packInfo && product.packInfo.length > 0) {
        html += '<div class="section">';
        html += '<div class="section-title">包装信息' + (isFromScrape ? '（规格）' : '') + ' <span class="badge">' + product.packInfo.length + '</span></div>';
        html += '<table class="sku-table"><thead><tr>';
        const packHeaders = Object.keys(product.packInfo[0]);
        for (const h of packHeaders) html += '<th>' + escapeHtml(h) + '</th>';
        html += '</tr></thead><tbody>';
        product.packInfo.forEach(row => {
            html += '<tr>';
            for (const h of packHeaders) html += '<td>' + escapeHtml(row[h] || '-') + '</td>';
            html += '</tr>';
        });
        html += '</tbody></table></div>';
    }

    // SKU images — slots match SKU count, position by filename number
    if (product.skuImages && product.skuImages.length > 0) {
        const skuCount = product.skus ? product.skus.length : product.skuImages.length;
        const totalSlots = Math.max(skuCount, product.skuImages.length);

        // Map file paths to their slot numbers (from filename "SKU图_03.jpg" → slot 3)
        const slotMap = [];
        product.skuImages.forEach((img) => {
            const fileName = img.split(/[\\/]/).pop();
            const match = fileName.match(/SKU图_(\d+)/);
            if (match) {
                const slotNum = parseInt(match[1], 10);
                slotMap[slotNum] = img;
            }
        });

        html += '<div class="section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
        html += '<div class="section-title">SKU图 <span class="badge">' + totalSlots + '</span></div>';
        html += '<div class="image-grid">';
        for (var si = 1; si <= totalSlots; si++) {
            var siFile = slotMap[si] || null;
            if (siFile) {
                var siName = siFile.split(/[\\/]/).pop();
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
                var siPlaceholderName = 'SKU图_' + String(si).padStart(2, '0') + '.jpg';
                var siPlaceholderPath = (product.productDir || '') + '\\SKU图\\' + siPlaceholderName;
                html += '<div class="img-item" data-filepath="' + escapeAttr(siPlaceholderPath) + '">';
                html += '<div class="img-placeholder" onclick="openUploadModal(this, \'' + escapeJs(siPlaceholderPath) + '\', \'SKU图_' + String(si).padStart(2, '0') + '\')">';
                html += '<div class="placeholder-icon">+</div>';
                html += '<div class="placeholder-text">' + escapeHtml(siPlaceholderName) + '</div>';
                html += '</div>';
                html += '<div class="img-actions-bar">';
                html += '<button class="action-btn btn-replace" data-path="' + escapeAttr(siPlaceholderPath) + '" onclick="replacePlaceholder(this)">替换</button>';
                html += '<button class="action-btn btn-delete" data-path="' + escapeAttr(siPlaceholderPath) + '" onclick="deleteImageByAttr(this)">删除</button>';
                html += '</div></div>';
            }
        }
        html += '</div></div>';
    }

    // SKU price table
    if (product.skus && product.skus.length > 0) {
        html += '<div class="section" data-product-dir="' + escapeAttr(product.productDir || '') + '">';
        html += '<div class="section-title">SKU 信息 <span class="badge">' + product.skus.length + '</span>';
        html += '<button class="sku-add-btn" onclick="addSkuRow(this, \'' + escapeJs(product.productDir || '') + '\')">新增</button></div>';
        html += '<table class="sku-table sku-table-draggable">';
        let fieldNames = [];
        if (product.skus.length > 0 && product.skus[0].detailFields) {
            const parts = product.skus[0].detailFields.split(', ');
            for (const p of parts) { const eqIdx = p.indexOf('='); if (eqIdx > 0) fieldNames.push(p.substring(0, eqIdx)); }
        }
        html += '<thead><tr><th>SKU图</th><th>规格名称</th>';
        for (const fn of fieldNames) html += '<th>' + escapeHtml(fn) + '</th>';
        html += '<th>批发价</th><th>最终价格</th><th>8折价</th><th>利润</th><th>库存</th><th></th></tr></thead>';
        html += '<tbody>';
        product.skus.forEach((sku, idx) => {
            html += '<tr class="sku-row" data-sku-index="' + idx + '" data-sku-id="' + escapeAttr(sku.skuId || '') + '">';
            html += '<td>' + (sku.imageUrl ? '<img class="sku-img" src="' + escapeHtml(sku.imageUrl.startsWith('http') || sku.imageUrl.startsWith('data:') ? sku.imageUrl : toFileUrl(sku.imageUrl) + '?t=' + Date.now()) + '" onclick="showModal(this.src)">' : '-') + '</td>';
            html += '<td class="sku-spec-name-cell" data-spec-name="1" data-product-dir="' + escapeAttr(product.productDir || '') + '">' + escapeHtml(sku.specName || '-') + '</td>';
            if (sku.detailFields) {
                const parts = sku.detailFields.split(', ');
                for (const p of parts) { const eqIdx = p.indexOf('='); html += '<td>' + escapeHtml(eqIdx > 0 ? p.substring(eqIdx + 1) : p) + '</td>'; }
            } else { for (let i = 0; i < fieldNames.length; i++) html += '<td>-</td>'; }
            const originalPrice = sku.originalPrice || 0;
            const finalPrice = sku.finalPrice || 0;
            const discountPrice = sku.discountPrice != null ? sku.discountPrice : finalPrice * 0.8;
            const profit = sku.profit != null ? sku.profit : discountPrice - originalPrice;
            html += '<td><span class="original-price">¥' + originalPrice.toFixed(2) + '</span></td>';
            html += '<td><span class="price">¥' + finalPrice.toFixed(2) + '</span></td>';
            html += '<td><span class="price">¥' + discountPrice.toFixed(2) + '</span></td>';
            html += '<td style="color:' + (profit >= 0 ? '#52c41a' : '#ff4d4f') + '">¥' + profit.toFixed(2) + '</td>';
            html += '<td>' + (sku.stock || 0) + '</td>';
            html += '<td><button class="action-btn sku-delete-btn" data-spec-name="' + escapeAttr(sku.specName || '') + '" onclick="deleteSkuRow(this)">删除</button></td>';
            html += '</tr>';
        });
        html += '</tbody></table></div>';
    }

    html += '</div>';
    return html;
}
