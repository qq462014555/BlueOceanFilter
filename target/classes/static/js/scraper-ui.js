// scraper-ui.js — 侧边栏、状态栏、日期加载等 UI 逻辑

function addSidebarItem(title, targetId) {
    const sidebar = document.getElementById('sidebar');
    const items = document.getElementById('sidebarItems');
    sidebar.classList.remove('hidden');
    const link = document.createElement('a');
    link.className = 'sidebar-item';
    link.textContent = title;
    link.href = '#' + targetId;
    link.onclick = function(e) {
        e.preventDefault();
        document.querySelectorAll('.sidebar-item').forEach(el => el.classList.remove('active'));
        link.classList.add('active');
        const target = document.getElementById(targetId);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
    };
    items.insertBefore(link, items.firstChild);
}

function showStatus(text, done) {
    const bar = document.getElementById('statusBar');
    bar.classList.remove('hidden');
    document.getElementById('statusText').textContent = text;
    if (done) bar.querySelector('.spinner').style.display = 'none';
    else bar.querySelector('.spinner').style.display = 'block';
}

function hideStatus() { document.getElementById('statusBar').classList.add('hidden'); }

function showError(msg) {
    document.getElementById('errorSection').classList.remove('hidden');
    document.getElementById('errorMessage').textContent = msg;
}

function hideError() { document.getElementById('errorSection').classList.add('hidden'); }

function showModal(src) {
    document.getElementById('modalImage').src = src;
    document.getElementById('imageModal').classList.add('active');
}

async function loadTodayProducts() {
    const today = new Date();
    const dateStr = today.toISOString().split('T')[0];
    document.getElementById('dateInput').value = dateStr;
    await loadProductsByDate();
}

async function loadProductsByDate() {
    const dateInput = document.getElementById('dateInput').value;
    const statusEl = document.getElementById('dateStatus');
    if (!dateInput) { statusEl.textContent = '请选择日期'; return; }
    const parts = dateInput.split('-');
    const formattedDate = `${parts[0]}年${parts[1]}月${parts[2]}日`;
    statusEl.textContent = '加载中...';
    try {
        const resp = await fetch('/api/files/load-products?date=' + encodeURIComponent(formattedDate));
        if (resp.ok) {
            const products = await resp.json();
            document.getElementById('results').innerHTML = '';
            document.getElementById('sidebarItems').innerHTML = '';
            if (products.length > 0) {
                products.forEach(p => renderLoadedProduct(p));
                statusEl.textContent = '已加载 ' + products.length + ' 个商品';
            } else {
                document.getElementById('results').innerHTML = '<div class="empty-state"><p>该日期没有采集数据</p></div>';
                statusEl.textContent = '无数据';
            }
        } else { statusEl.textContent = '加载失败: ' + (await resp.json()).message; }
    } catch (e) { statusEl.textContent = '请求失败: ' + e.message; }
}
