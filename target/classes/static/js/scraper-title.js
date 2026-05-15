// scraper-title.js — 标题编辑

function startEditTitleFromText(textEl) {
    const titleContainer = textEl.closest('.product-title');
    if (titleContainer.querySelector('.product-title-input')) return;
    const currentTitle = textEl.textContent.trim();
    const input = document.createElement('input');
    input.type = 'text'; input.className = 'product-title-input'; input.value = currentTitle;
    textEl.replaceWith(input); input.focus(); input.select();
    const editBtn = titleContainer.querySelector('.edit-btn');
    if (editBtn) editBtn.outerHTML = '<button class="save-btn" onclick="saveTitle(this)">保存</button><button class="cancel-btn" onclick="cancelEditTitle(this)">取消</button>';
}

function startEditTitle(btn) {
    const titleContainer = btn.closest('.product-title');
    const textEl = titleContainer.querySelector('.title-text');
    const currentTitle = textEl.textContent.trim();
    const input = document.createElement('input');
    input.type = 'text'; input.className = 'product-title-input'; input.value = currentTitle;
    textEl.replaceWith(input); input.focus(); input.select();
    btn.outerHTML = '<button class="save-btn" onclick="saveTitle(this)">保存</button><button class="cancel-btn" onclick="cancelEditTitle(this)">取消</button>';
}

async function saveTitle(btn) {
    const titleContainer = btn.closest('.product-title');
    const input = titleContainer.querySelector('.product-title-input');
    const newTitle = input.value.trim();
    const productDir = unescapeAttr(titleContainer.getAttribute('data-product-dir'));
    if (!newTitle || !productDir) { cancelEditTitle(btn); return; }
    btn.textContent = '保存中...'; btn.disabled = true;
    try {
        const resp = await fetch('/api/files/update-title', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, title: newTitle })
        });
        let data;
        const contentType = resp.headers.get('content-type');
        if (contentType && contentType.includes('application/json')) data = await resp.json();
        else { const text = await resp.text(); data = { error: text }; }
        if (resp.ok) {
            input.outerHTML = '<span class="title-text">' + escapeHtml(newTitle) + '</span>';
            const saveBtn = titleContainer.querySelector('.save-btn');
            if (saveBtn) saveBtn.outerHTML = '<button class="edit-btn" onclick="startEditTitle(this)">编辑</button>';
            const cancelBtn = titleContainer.querySelector('.cancel-btn');
            if (cancelBtn) cancelBtn.remove();
            if (data.newPath) {
                titleContainer.setAttribute('data-product-dir', escapeAttr(data.newPath));
                const cardEl = titleContainer.closest('.product-card');
                if (cardEl) { const metaDir = cardEl.querySelector('.product-meta span'); if (metaDir) metaDir.textContent = '目录: ' + data.newPath; }
            }
            const cardEl2 = titleContainer.closest('.product-card');
            const productId = cardEl2 ? cardEl2.id : null;
            if (productId) {
                const sidebarLink = document.querySelector('.sidebar-item[href="#' + productId + '"]');
                if (sidebarLink) sidebarLink.textContent = newTitle;
            }
        } else { showToast('标题修改失败: ' + (data.error || data.message), 'error'); btn.textContent = '保存'; btn.disabled = false; }
    } catch (e) { showToast('请求失败: ' + e.message, 'error'); btn.textContent = '保存'; btn.disabled = false; }
}

function cancelEditTitle(btn) {
    const titleContainer = btn.closest('.product-title');
    const input = titleContainer.querySelector('.product-title-input');
    if (!input) return;
    const currentTitle = input.value.trim() || '未知商品';
    input.outerHTML = '<span class="title-text">' + escapeHtml(currentTitle) + '</span>';
    const saveBtn = titleContainer.querySelector('.save-btn');
    if (saveBtn) saveBtn.outerHTML = '<button class="edit-btn" onclick="startEditTitle(this)">编辑</button>';
    const cancelBtn2 = titleContainer.querySelector('.cancel-btn');
    if (cancelBtn2) cancelBtn2.remove();
}
