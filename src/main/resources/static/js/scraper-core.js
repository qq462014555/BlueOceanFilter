// scraper-core.js — 采集核心逻辑（单链接/批量/RPA/Chrome启动/轮询）

let pollingTimer = null;

function updateStatus(data) {
    document.getElementById('statusText').textContent = data.message;
    document.getElementById('statusProgress').textContent = data.total > 0 ? data.processed + ' / ' + data.total : '';
}

async function scrapeSingle() {
    const url = document.getElementById('urlInput').value.trim();
    if (!url) { showToast('请输入商品链接', 'error'); return; }
    hideError();
    const btn = document.getElementById('scrapeBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    showStatus('正在抓取: ' + url);
    try {
        const resp = await fetch('/api/scraper/scrape?url=' + encodeURIComponent(url));
        if (resp.ok) { renderProduct(await resp.json()); }
        else {
            try { const data = await resp.json(); showError(data.error || data.message || '抓取失败，请稍后重试'); }
            catch { showError(await resp.text() || '抓取失败，请稍后重试'); }
        }
    } catch (e) { showError('请求失败: ' + e.message); }
    finally { btn.disabled = false; btn.textContent = '开始采集'; hideStatus(); }
}

async function startBatch() {
    const btn = document.getElementById('batchBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    document.getElementById('results').innerHTML = '';
    showStatus('启动批量采集任务...');
    try {
        await fetch('/api/scraper/start', { method: 'POST' });
        startPolling();
    } catch (e) { showToast('启动失败: ' + e.message, 'error'); btn.disabled = false; btn.textContent = '批量抓取全部商品'; }
}

async function startFromRpa() {
    const btn = document.getElementById('rpaBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    document.getElementById('results').innerHTML = '';
    showStatus('从RPA文件启动采集...');
    try {
        await fetch('/api/scraper/start-from-rpa', { method: 'POST' });
        startPolling();
    } catch (e) { showToast('启动失败: ' + e.message, 'error'); btn.disabled = false; btn.textContent = '从RPA文件获取'; }
}

async function launchChrome() {
    const btn = document.getElementById('chromeBtn');
    btn.disabled = true; btn.textContent = '启动中...';
    try { await fetch('/api/scraper/launch-chrome', { method: 'POST' }); }
    catch (e) { showToast('请求失败: ' + e.message, 'error'); }
    finally { btn.disabled = false; btn.textContent = '启动Chrome(9222-采集)'; }
}

async function launchChromeMerchant() {
    const btn = document.getElementById('chromeMerchantBtn');
    btn.disabled = true; btn.textContent = '启动中...';
    try { await fetch('/api/scraper/launch-chrome-merchant', { method: 'POST' }); }
    catch (e) { showToast('请求失败: ' + e.message, 'error'); }
    finally { btn.disabled = false; btn.textContent = '启动Chrome(9223-商家后台)'; }
}

function startPolling() {
    pollingTimer = setInterval(async () => {
        try {
            const resp = await fetch('/api/scraper/status');
            const data = await resp.json();
            updateStatus(data);
            if (!data.running) {
                clearInterval(pollingTimer);
                document.getElementById('batchBtn').disabled = false;
                document.getElementById('batchBtn').textContent = '批量抓取全部商品';
                document.getElementById('rpaBtn').disabled = false;
                document.getElementById('rpaBtn').textContent = '从RPA文件获取';
                showStatus(data.message, true);
                if (data.resultCount > 0) {
                    const resp2 = await fetch('/api/scraper/results');
                    const products = await resp2.json();
                    document.getElementById('results').innerHTML = '';
                    products.forEach(p => renderProduct(p));
                }
                setTimeout(hideStatus, 5000);
            }
        } catch (e) { clearInterval(pollingTimer); }
    }, 2000);
}

// 页面加载时加载今天的数据
window.addEventListener('DOMContentLoaded', loadTodayProducts);
