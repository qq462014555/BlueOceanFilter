// scraper-core.js — 采集核心逻辑（单链接/批量/RPA/Chrome启动/轮询）
// 注意：showStatus/hideStatus/showError/hideError 定义在 scraper-ui.js 中

let pollingTimer = null;
let logEventSource = null;

// ========== 控制台日志面板 ==========

function showConsole() {
    const modal = document.getElementById('consoleModal');
    if (modal) modal.classList.add('active');
}

function hideConsole() {
    const modal = document.getElementById('consoleModal');
    if (modal) modal.classList.remove('active');
}

let logLineCount = 0;

function appendLogLine(text) {
    const body = document.getElementById('consoleBody');
    if (!body) return;
    const div = document.createElement('div');
    div.className = 'console-line';
    if (text.includes('✅') || text.includes('成功')) div.classList.add('success');
    else if (text.includes('❌') || text.includes('失败') || text.includes('异常')) div.classList.add('error');
    else if (text.includes('步骤')) div.classList.add('step');
    else if (text.includes('⚠') || text.includes('重试')) div.classList.add('warn');
    else div.classList.add('info');
    div.textContent = text;
    body.appendChild(div);
    body.scrollTop = body.scrollHeight;
    logLineCount++;
    if (logLineCount > 500) {
        for (let i = 0; i < 200 && body.firstChild; i++) body.removeChild(body.firstChild);
        logLineCount -= 200;
    }
}

function clearConsole() {
    const body = document.getElementById('consoleBody');
    if (body) body.innerHTML = '';
    logLineCount = 0;
}

/** 通过 SSE 实时接收日志 */
function startLogStream() {
    // 关闭旧的连接
    if (logEventSource) logEventSource.close();

    logEventSource = new EventSource('/api/scraper/log-stream');

    logEventSource.addEventListener('log', function(e) {
        appendLogLine(e.data);
    });

    logEventSource.addEventListener('complete', function() {
        logEventSource.close();
        logEventSource = null;
    });

    logEventSource.onerror = function() {
        // 连接断开后自动重连（EventSource 原生行为）
    };
}

function stopLogStream() {
    if (logEventSource) {
        logEventSource.close();
        logEventSource = null;
    }
}

// ========== 采集操作 ==========

async function scrapeSingle() {
    const url = document.getElementById('urlInput').value.trim();
    if (!url) { showToast('请输入商品链接', 'error'); return; }
    hideError();
    const btn = document.getElementById('scrapeBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    showStatus('正在抓取: ' + url);
    clearConsole();
    showConsole();
    startLogStream();
    appendLogLine('开始采集: ' + url);
    try {
        const resp = await fetch('/api/scraper/scrape?url=' + encodeURIComponent(url));
        if (resp.ok) {
            appendLogLine('✅ 采集完成，渲染结果...');
            renderProduct(await resp.json());
        }
        else {
            try { const data = await resp.json(); showError(data.error || data.message || '抓取失败，请稍后重试'); }
            catch { showError(await resp.text() || '抓取失败，请稍后重试'); }
        }
    } catch (e) { showError('请求失败: ' + e.message); }
    finally {
        btn.disabled = false; btn.textContent = '开始采集';
        hideStatus();
        stopLogStream();
    }
}

async function startBatch() {
    const btn = document.getElementById('batchBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    document.getElementById('results').innerHTML = '';
    clearConsole();
    showConsole();
    showStatus('启动批量采集任务...');
    startLogStream();
    appendLogLine('启动批量采集任务...');
    try {
        await fetch('/api/scraper/start', { method: 'POST' });
        startPolling();
    } catch (e) { showToast('启动失败: ' + e.message, 'error'); btn.disabled = false; btn.textContent = '批量抓取全部商品'; }
}

async function startFromRpa() {
    const btn = document.getElementById('rpaBtn');
    btn.disabled = true; btn.textContent = '采集中...';
    document.getElementById('results').innerHTML = '';
    clearConsole();
    showConsole();
    showStatus('从RPA文件启动采集...');
    // 先连接 SSE 再启动采集，确保不会漏掉日志
    startLogStream();
    appendLogLine('从RPA文件启动采集...');
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

// ========== 轮询 ==========

function updateStatus(data) {
    document.getElementById('statusText').textContent = data.message;
    document.getElementById('statusProgress').textContent = data.total > 0 ? data.processed + ' / ' + data.total : '';
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
            }
        } catch (e) { clearInterval(pollingTimer); }
    }, 2000);
}

// 页面加载时加载今天的数据
window.addEventListener('DOMContentLoaded', loadTodayProducts);

// 页面加载后根据 URL 参数滚动到对应商品
window.addEventListener('DOMContentLoaded', function() {
    setTimeout(function() {
        var params = new URLSearchParams(window.location.search);
        var p = params.get('p');
        if (p) {
            // Find card by data-product-dir
            var cards = document.querySelectorAll('.product-card[data-product-dir]');
            for (var i = 0; i < cards.length; i++) {
                var dir = cards[i].getAttribute('data-product-dir');
                if (dir && dir.split(/[\\/]/).pop() === decodeURIComponent(p)) {
                    cards[i].scrollIntoView({ behavior: 'smooth', block: 'start' });
                    // Highlight matching sidebar item
                    var links = document.querySelectorAll('.sidebar-item');
                    for (var j = 0; j < links.length; j++) {
                        if (links[j].getAttribute('href') === window.location.pathname + '?p=' + encodeURIComponent(p)) {
                            links[j].classList.add('active');
                        }
                    }
                    break;
                }
            }
        }
    }, 500);
});