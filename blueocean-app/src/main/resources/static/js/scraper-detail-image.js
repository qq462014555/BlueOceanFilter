// scraper-detail-image.js — 详情图拖拽排序 (SortableJS)
// 前端只回显后端数据，不处理重排序逻辑

const _detailSortableMap = new Map();

function setupDetailDrag(card) {
    const section = card.querySelector('.detail-section');
    if (!section) return;
    const grid = section.querySelector('.image-grid');
    if (!grid) return;

    // 销毁已有实例
    const existing = _detailSortableMap.get(grid);
    if (existing) { existing.destroy(); _detailSortableMap.delete(grid); }

    const sortableInstance = new Sortable(grid, {
        animation: 200,
        ghostClass: 'sortable-ghost',
        chosenClass: 'sortable-chosen',
        dragClass: 'sortable-drag',
        delay: 0,
        filter: '.img-placeholder',
        preventOnFilter: false,
        onEnd: function(evt) {
            if (_detailSortableMap.get(grid) !== sortableInstance) return;
            if (evt.newIndex === evt.oldIndex) return;
            setTimeout(async () => {
                if (_detailSortableMap.get(grid) !== sortableInstance) return;
                // 发顺序给后端 → 回显
                await detailSyncReorder(grid, section);
                await reloadDetailImages(section);
            }, 0);
        }
    });
    _detailSortableMap.set(grid, sortableInstance);
}

async function detailSyncReorder(grid, section) {
    const card = grid.closest('.product-card');
    const titleEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    let productDir = titleEl ? titleEl.getAttribute('data-product-dir') || '' : '';
    if (!productDir) {
        const firstItem = grid.querySelector('.img-item[data-filepath]');
        if (firstItem) {
            const fp = firstItem.getAttribute('data-filepath') || '';
            const idx = fp.lastIndexOf('\\详情图');
            if (idx > 0) productDir = fp.substring(0, idx);
        }
    }
    if (!productDir) return;

    const orderedPaths = [];
    grid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
        const fp = item.getAttribute('data-filepath');
        if (fp && (fp.indexOf('\\详情图') >= 0 || fp.indexOf('/详情图') >= 0)) {
            orderedPaths.push(fp);
        }
    });

    if (orderedPaths.length === 0) return;

    // 显示 loading
    const badge = section.querySelector('.section-title .badge');
    const oldText = badge ? badge.textContent : '';
    if (badge) badge.textContent = '...';
    grid.style.pointerEvents = 'none';
    grid.style.opacity = '0.6';

    try {
        const resp = await fetch('/api/files/reorder-detail-images', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, orderedPaths })
        });
        const respData = await resp.json();
        if (!resp.ok || respData.error) {
            showToast('详情图重排失败: ' + (respData.error || ''), 'error');
        }
    } catch (e) {
        showToast('详情图重排失败: ' + e.message, 'error');
    } finally {
        if (badge) badge.textContent = oldText;
        grid.style.pointerEvents = '';
        grid.style.opacity = '';
    }
}
