// scraper-main-image.js — 主图拖拽排序 (SortableJS)

function setupMainImageDrag(card) {
    card.querySelectorAll('.image-grid').forEach(grid => {
        const section = grid.closest('.section');
        if (!section) return;
        const titleEl = section.querySelector('.section-title');
        if (!titleEl || !titleEl.textContent.trim().startsWith('主图')) return;

        // SortableJS 接管
        new Sortable(grid, {
            animation: 200,
            ghostClass: 'sortable-ghost',
            chosenClass: 'sortable-chosen',
            dragClass: 'sortable-drag',
            delay: 0,
            filter: '.img-placeholder', // 跳过占位框
            preventOnFilter: false,
            onEnd: function(evt) {
                if (evt.newIndex === evt.oldIndex) return;
                miSyncReorder(grid);
            }
        });
    });
}

async function miSyncReorder(grid) {
    const section = grid.closest('.section');
    const card = grid.closest('.product-card');
    const titleEl = card ? card.querySelector('.product-title[data-product-dir]') : null;
    let productDir = titleEl ? titleEl.getAttribute('data-product-dir') || '' : '';
    if (!productDir) {
        const firstItem = grid.querySelector('.img-item[data-filepath]');
        if (firstItem) {
            const fp = firstItem.getAttribute('data-filepath') || '';
            const mainIdx = fp.lastIndexOf('\\主图');
            if (mainIdx > 0) productDir = fp.substring(0, mainIdx);
        }
    }
    if (!productDir) return;

    const orderedPaths = [];
    const mainItems = [];
    grid.querySelectorAll('.img-item[data-filepath]').forEach(item => {
        const fp = item.getAttribute('data-filepath');
        if (fp && item.querySelector('img')) {
            orderedPaths.push(fp);
            mainItems.push(item);
        }
    });

    try {
        const resp = await fetch('/api/files/reorder-main-images', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, orderedPaths })
        });
        const respData = await resp.json();
        if (!resp.ok || respData.error) {
            showToast('文件重排失败: ' + (respData.error || ''), 'error');
            return;
        }
    } catch (e) {
        showToast('文件重排失败: ' + e.message, 'error');
        return;
    }

    const subDir = productDir + '\\主图\\';
    for (let i = 0; i < mainItems.length; i++) {
        const item = mainItems[i];
        const oldPath = item.getAttribute('data-filepath');
        const oldName = oldPath.split(/[\\/]/).pop();
        let ext = '';
        const dotIdx = oldName.lastIndexOf('.');
        if (dotIdx >= 0) ext = oldName.substring(dotIdx);
        const num = String(i + 1).padStart(2, '0');
        const newName = '主图_' + num + ext;
        const newPath = subDir + newName;
        item.setAttribute('data-filepath', newPath);
        item.querySelectorAll('.action-btn[data-path]').forEach(btn => btn.setAttribute('data-path', newPath));
    }

    showToast('已重排序', 'success');
}
