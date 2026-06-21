// scraper-sku-image.js — SKU 图拖拽排序 (SortableJS) + SKU 规格名称编辑

function setupSkuImageDrag(card) {
    card.querySelectorAll('.section').forEach(section => {
        const titleEl = section.querySelector('.section-title');
        if (!titleEl || !titleEl.textContent.trim().startsWith('SKU图')) return;

        const grid = section.querySelector('.image-grid');
        if (!grid) return;

        new Sortable(grid, {
            animation: 200,
            ghostClass: 'sortable-ghost',
            chosenClass: 'sortable-chosen',
            dragClass: 'sortable-drag',
            delay: 0,
            filter: '.img-placeholder',
            preventOnFilter: false,
            onEnd: function(evt) {
                if (evt.newIndex === evt.oldIndex) return;
                skuImageSyncReorder(grid, section);
            }
        });
    });
}

async function skuImageSyncReorder(grid, section) {
    const card = grid.closest('.product-card');
    const titleEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    let productDir = titleEl ? titleEl.getAttribute('data-product-dir') || '' : '';
    if (!productDir) {
        const firstItem = grid.querySelector('.img-item[data-filepath]');
        if (firstItem) {
            const fp = firstItem.getAttribute('data-filepath') || '';
            const idx = fp.lastIndexOf('\\SKU图');
            if (idx > 0) productDir = fp.substring(0, idx);
        }
    }
    if (!productDir) return;

    const orderedPaths = [];
    const skuItems = [];
    grid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
        const fp = item.getAttribute('data-filepath');
        if (fp && (fp.indexOf('\\SKU图') >= 0 || fp.indexOf('/SKU图') >= 0) && item.querySelector('img')) {
            orderedPaths.push(fp);
            skuItems.push(item);
        }
    });

    if (orderedPaths.length === 0) return;

    try {
        const resp = await fetch('/api/files/reorder-sku-images', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, orderedPaths })
        });
        const respData = await resp.json();
        if (!resp.ok || respData.error) {
            showToast('SKU 图重排失败: ' + (respData.error || ''), 'error');
            return;
        }
    } catch (e) {
        showToast('SKU 图重排失败: ' + e.message, 'error');
        return;
    }

    const subDir = productDir + '\\SKU图\\';
    for (let i = 0; i < skuItems.length; i++) {
        const item = skuItems[i];
        const oldPath = item.getAttribute('data-filepath');
        const oldName = oldPath.split(/[\\/]/).pop();
        let ext = '';
        const dotIdx = oldName.lastIndexOf('.');
        if (dotIdx >= 0) ext = oldName.substring(dotIdx);
        const num = String(i + 1).padStart(2, '0');
        const newName = 'SKU图_' + num + ext;
        const newPath = subDir + newName;
        item.setAttribute('data-filepath', newPath);
        item.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', newPath));
    }

    const badge = section.querySelector('.section-title .badge');
    if (badge) badge.textContent = skuItems.length;

    // Refresh SKU info table images
    skuImageRefreshTable(card, grid);

    showToast('已重排序', 'success');
}

/**
 * 更新 SKU 信息表里的 SKU 图：从 grid 读取当前状态，同步更新 table 首列图片
 */
function skuImageRefreshTable(card, grid) {
    const skuTable = card.querySelector('.sku-table-draggable tbody');
    if (!skuTable) return;

    // Build grid image data: only items with actual <img> element count
    const gridItems = [];
    grid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
        const fp = item.getAttribute('data-filepath');
        const hasImg = !!item.querySelector('img');
        gridItems.push({ path: fp || '', hasImg });
    });

    const rows = skuTable.querySelectorAll('.sku-row');
    for (let i = 0; i < rows.length; i++) {
        const imgCell = rows[i].querySelector('td');
        if (!imgCell) continue;

        const item = gridItems[i];
        const existingImg = imgCell.querySelector('.sku-img');
        if (item && item.hasImg && item.path.indexOf('SKU图_') >= 0) {
            const imgPathUrl = toFileUrl(item.path);
            if (existingImg) {
                existingImg.src = imgPathUrl + '?t=' + Date.now();
                existingImg.onclick = function() { showModal(this.src); };
            } else {
                // grid 有图片但 table 没有，创建 img 元素
                imgCell.innerHTML = '<img class="sku-img" src="' + imgPathUrl + '" onclick="showModal(this.src)" alt="">';
            }
        } else {
            if (existingImg) {
                imgCell.innerHTML = '-';
            }
        }
    }
}

/**
 * 双击 SKU 规格名称，进入编辑
 */
document.addEventListener('dblclick', function(e) {
    const td = e.target.closest('.sku-spec-name-cell');
    if (!td || td.querySelector('input')) return;
    const productDir = td.getAttribute('data-product-dir');
    const oldName = td.textContent.trim();
    if (!productDir || !oldName || oldName === '-') return;

    const input = document.createElement('input');
    input.className = 'sku-spec-name-input';
    input.value = oldName;
    td.textContent = '';
    td.appendChild(input);
    input.focus();
    input.select();

    const finish = async () => {
        const newName = input.value.trim();
        if (!newName || newName === oldName) { td.textContent = oldName; return; }
        try {
            const resp = await fetch('/api/files/rename-sku', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ productDir, oldName, newName })
            });
            const data = await resp.json();
            if (!resp.ok || data.error) { showToast('失败: ' + (data.error || data.message), 'error'); td.textContent = oldName; return; }
            td.textContent = newName;
            showToast('已更新', 'success');
        } catch (e) {
            showToast('失败: ' + e.message, 'error');
            td.textContent = oldName;
        }
    };

    input.onblur = finish;
    input.onkeydown = (e) => {
        if (e.key === 'Enter') { input.onblur = null; input.blur(); }
        if (e.key === 'Escape') { input.value = oldName; input.onblur = null; input.blur(); }
    };
});
