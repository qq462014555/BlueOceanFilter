// scraper-ui.js — 侧边栏、状态栏、日期加载等 UI 逻辑

function addSidebarItem(title, targetId) {
    const sidebar = document.getElementById('sidebar');
    const items = document.getElementById('sidebarItems');
    sidebar.classList.remove('hidden');
    const link = document.createElement('a');
    link.className = 'sidebar-item';
    link.textContent = title;
    link.href = '/scraper.html?p=' + encodeURIComponent(title);
    link.onclick = function(e) {
        e.preventDefault();
        document.querySelectorAll('.sidebar-item').forEach(el => el.classList.remove('active'));
        link.classList.add('active');
        const target = document.getElementById(targetId);
        if (target) target.scrollIntoView({ behavior: 'smooth', block: 'start' });
        window.history.pushState(null, '', '/scraper.html?p=' + encodeURIComponent(title));
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

// ========== 图片画廊 ==========
let _galleryImages = [];
let _galleryIndex = 0;

function showModal(src, gallery, index) {
    const modal = document.getElementById('imageModal');
    const img = document.getElementById('modalImage');
    const prev = document.getElementById('modalPrev');
    const next = document.getElementById('modalNext');
    const counter = document.getElementById('modalCounter');
    if (!modal) return;
    img.src = src;
    if (gallery && gallery.length > 1) {
        _galleryImages = gallery;
        _galleryIndex = index !== undefined ? index : 0;
        if (prev) prev.style.display = _galleryIndex > 0 ? 'block' : 'none';
        if (next) next.style.display = _galleryIndex < _galleryImages.length - 1 ? 'block' : 'none';
        if (counter) counter.textContent = (_galleryIndex + 1) + ' / ' + _galleryImages.length;
    } else {
        _galleryImages = [];
        if (prev) prev.style.display = 'none';
        if (next) next.style.display = 'none';
        if (counter) counter.textContent = '';
    }
    modal.classList.add('active');
}

function closeModal() {
    const modal = document.getElementById('imageModal');
    if (modal) modal.classList.remove('active');
}

function galleryPrev() {
    if (_galleryIndex <= 0 || _galleryImages.length === 0) return;
    _galleryIndex--;
    document.getElementById('modalImage').src = _galleryImages[_galleryIndex];
    const prev = document.getElementById('modalPrev');
    const next = document.getElementById('modalNext');
    const counter = document.getElementById('modalCounter');
    if (prev) prev.style.display = _galleryIndex > 0 ? 'block' : 'none';
    if (next) next.style.display = 'block';
    if (counter) counter.textContent = (_galleryIndex + 1) + ' / ' + _galleryImages.length;
}

function galleryNext() {
    if (_galleryIndex >= _galleryImages.length - 1 || _galleryImages.length === 0) return;
    _galleryIndex++;
    document.getElementById('modalImage').src = _galleryImages[_galleryIndex];
    const prev = document.getElementById('modalPrev');
    const next = document.getElementById('modalNext');
    const counter = document.getElementById('modalCounter');
    if (next) next.style.display = _galleryIndex < _galleryImages.length - 1 ? 'block' : 'none';
    if (prev) prev.style.display = 'block';
    if (counter) counter.textContent = (_galleryIndex + 1) + ' / ' + _galleryImages.length;
}

// 键盘切换
document.addEventListener('keydown', function(e) {
    const modal = document.getElementById('imageModal');
    if (!modal || !modal.classList.contains('active')) return;
    if (e.key === 'ArrowLeft') galleryPrev();
    else if (e.key === 'ArrowRight') galleryNext();
    else if (e.key === 'Escape') closeModal();
});

// 复制图片到剪贴板（1440×1440）
function copyAiImage(imgSrc) {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = function() {
        const canvas = document.createElement('canvas');
        canvas.width = 1440; canvas.height = 1440;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, 1440, 1440);
        const scale = Math.min(1440 / img.width, 1440 / img.height);
        const x = (1440 - img.width * scale) / 2;
        const y = (1440 - img.height * scale) / 2;
        ctx.drawImage(img, x, y, img.width * scale, img.height * scale);
        canvas.toBlob(function(blob) {
            navigator.clipboard.write([new ClipboardItem({[blob.type]: blob})])
                .then(() => showToast('✅ 已复制 1440×1440 图片', 'success'))
                .catch(() => showToast('❌ 复制失败', 'error'));
        }, 'image/png');
    };
    img.onerror = () => showToast('❌ 加载图片失败', 'error');
    img.src = imgSrc;
}

// ========== AI 主图重绘弹窗 ==========

let _aiRedrawProductDir = '';
let _aiRedrawPlatform = 'taobao';
let _aiRedrawPrompts = {};
let _aiRedrawAnalysis = {};
let _aiReplaceImages = [];
let _aiReplacePrompts = [];
let _aiReplaceResults = [];
let _aiGenTimer = null;
let _aiGenSeconds = 0;
const _aiPlatformNames = { taobao: '淘宝', douyin: '抖音', shopee: '虾皮' };

// 多商品独立缓存
let _analysisCache = {};
let _promptsCache = {};
let _replaceCache = {};

function saveCurrentToCache() {
    const d = _aiRedrawProductDir;
    if (!d) return;
    _analysisCache[d] = JSON.parse(JSON.stringify(_aiRedrawAnalysis));
    if (_aiRedrawPrompts[d]) _promptsCache[d] = JSON.parse(JSON.stringify(_aiRedrawPrompts[d]));
    _replaceCache[d] = {
        images: [..._aiReplaceImages],
        prompts: [..._aiReplacePrompts],
        results: JSON.parse(JSON.stringify(_aiReplaceResults))
    };
}

function loadFromCache(d) {
    if (_analysisCache[d]) _aiRedrawAnalysis = JSON.parse(JSON.stringify(_analysisCache[d]));
    else _aiRedrawAnalysis = {};
    if (_promptsCache[d]) _aiRedrawPrompts[d] = JSON.parse(JSON.stringify(_promptsCache[d]));
    else _aiRedrawPrompts[d] = {};
    if (_replaceCache[d]) {
        _aiReplaceImages = [..._replaceCache[d].images];
        _aiReplacePrompts = [..._replaceCache[d].prompts];
        _aiReplaceResults = JSON.parse(JSON.stringify(_replaceCache[d].results));
    } else {
        _aiReplaceImages = []; _aiReplacePrompts = []; _aiReplaceResults = [];
    }
}

function openAiRedrawModal(productDir) {
    // 保存当前商品状态
    saveCurrentToCache();
    // 切换到新商品
    _aiRedrawProductDir = productDir;
    loadFromCache(productDir);

    // 渲染替换图
    const rg = document.getElementById('aiReplaceGrid');
    if (rg) {
        if (_aiReplaceImages.length > 0) {
            renderReplaceGrid();
        } else {
            rg.innerHTML = '<div class="img-item"><div class="img-placeholder" onclick="openReplaceUpload(this)"><div class="placeholder-icon">+</div><div class="placeholder-text">添加图</div></div></div>';
        }
    }
    document.querySelectorAll('.ai-replace-results').forEach(el => el.remove());
    document.querySelectorAll('.ai-whitebg-grid').forEach(el => el.remove());
    _aiWhiteBgLoading = false;

    document.getElementById('aiRedrawModal').classList.add('active');
    loadAiPrompts();
    const genTab = document.querySelector('#aiTabBar .ai-tab[data-tab="generate"]');
    if (genTab) genTab.click();

    // 恢复替换结果（优先从缓存，否则从文件加载）
    if (_aiReplaceResults.length > 0) {
        showReplaceResults();
    } else {
        loadExistingReplaceResults(productDir);
    }

    // 有缓存直接渲染，不重新请求
    if (Object.keys(_aiRedrawAnalysis).length > 0) {
        renderAiAnalysis(_aiRedrawAnalysis);
        renderAiPromptGrid();
        loadWhiteBgImages(productDir);
        loadGeneratedMainImages(productDir);
        return;
    }
    autoGeneratePrompts(productDir);
}

function closeAiRedrawModal() {
    document.getElementById('aiRedrawModal').classList.remove('active');
}

async function loadAiPrompts() {
    try {
        const resp = await fetch('/api/ai-image/prompts');
        if (resp.ok) {
            const data = await resp.json();
            _aiRedrawPrompts = data.platforms || {};
            renderAiModelSelect(data.models || []);
        }
    } catch (e) {
        console.error('加载提示词失败', e);
    }
}

// 两个模型下拉同步
document.addEventListener('change', function(e) {
    if (e.target.id === 'aiModelSelect') {
        const other = document.getElementById('aiReplaceModelSelect');
        if (other) other.value = e.target.value;
    } else if (e.target.id === 'aiReplaceModelSelect') {
        const other = document.getElementById('aiModelSelect');
        if (other) other.value = e.target.value;
    }
});

function renderAiModelSelect(models) {
    const selects = ['aiModelSelect', 'aiReplaceModelSelect'];
    selects.forEach(id => {
        const sel = document.getElementById(id);
        if (!sel) return;
        const prevVal = sel.value; // 保存当前选中值
        sel.innerHTML = '';
        if (models.length > 0) {
            models.forEach(m => {
                const opt = document.createElement('option');
                opt.value = m.id; opt.textContent = m.name;
                sel.appendChild(opt);
            });
        } else {
            sel.innerHTML = '<option value="black-forest-labs/FLUX.1-schnell">FLUX.1 Schnell</option>';
        }
        // 恢复之前的选中值，无则默认 Image 2
        if (prevVal) {
            for (const opt of sel.options) {
                if (opt.value === prevVal) { sel.value = prevVal; break; }
            }
        } else {
            for (const opt of sel.options) {
                if (opt.value === 'openai/gpt-image-2') { sel.value = opt.value; break; }
            }
        }
    });
}

function renderAiPromptGrid() {
    const grid = document.getElementById('aiPromptGrid');
    if (!grid) return;
    const prompts = _aiRedrawPrompts[_aiRedrawPlatform] || {};
    let html = '';
    // 全选按钮
    html += '<div style="grid-column:1/-1;margin-bottom:4px;display:flex;justify-content:flex-end;">';
    html += '<label style="font-size:12px;color:#667eea;cursor:pointer;"><input type="checkbox" id="selectAllPrompt" checked onchange="toggleAllPrompts(this)"> 一键全选</label>';
    html += '</div>';
    for (let i = 1; i <= 4; i++) {
        const key = '图' + i;
        const val = prompts[key] || '';
        html += '<div class="prompt-item" style="display:flex;flex-direction:column;gap:4px;">';
        html += '<div class="prompt-item-header">';
        html += '<span class="img-label">' + (_aiPlatformNames[_aiRedrawPlatform] || _aiRedrawPlatform) + ' - ' + key + '</span>';
        html += '<label class="prompt-check"><input type="checkbox" data-check-key="' + key + '" checked> 是否生成图</label>';
        html += '</div>';
        html += '<textarea data-key="' + key + '" placeholder="输入生成风格提示词..." style="width:100%;height:300px;">' + escapeHtml(val) + '</textarea>';
        // 图片展示区（横向画廊，新图追加在右侧）
        html += '<div class="ai-gen-slot" id="aiGenSlot_' + key + '" style="display:none;flex-direction:row;gap:8px;overflow-x:auto;padding:4px 0;"></div>';
        html += '<button class="btn-optimize-prompt" data-key="' + key + '" onclick="optimizePrompt(this)">🧠 智能优化提示词</button>';
        html += '</div>';
    }
    grid.innerHTML = html;
}

function toggleAllPrompts(cb) {
    document.querySelectorAll('#aiPromptGrid input[data-check-key]').forEach(chk => {
        chk.checked = cb.checked;
    });
}

async function optimizePrompt(btn) {
    if (_aiGenTimer) { showToast('⚠️ AI 商品分析进行中，请等待完成', 'error'); return; }
    const key = btn.dataset.key;
    const textarea = btn.parentElement.querySelector('textarea');
    const prompt = textarea.value.trim();
    if (!prompt) { showToast('⚠️ 提示词为空', 'error'); return; }
    const analysis = _aiRedrawAnalysis || {};
    if (Object.keys(analysis).length === 0) { showToast('⚠️ 缺少商品分析', 'error'); return; }
    btn.disabled = true;
    btn.textContent = '⏳ 优化中...';
    try {
        const resp = await fetch('/api/ai-image/optimize-prompt', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt, analysis })
        });
        if (resp.ok) {
            const data = await resp.json();
            if (data.success && data.optimizedPrompt) {
                textarea.value = data.optimizedPrompt;
                showToast('✅ 已优化', 'success');
            } else showToast('❌ 优化失败', 'error');
        }
    } catch (e) {
        showToast('❌ 异常: ' + e.message, 'error');
    }
    btn.disabled = false;
    btn.textContent = '🧠 智能优化提示词';
}

// AI 分析

function showAiLoading() {
    stopAiLoading(); // 清除旧计时器
    const section = document.getElementById('aiAnalysisSection');
    const grid = document.getElementById('aiAnalysisGrid');
    if (!section || !grid) return;
    section.style.display = 'block';
    _aiGenSeconds = 0;
    grid.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:20px;color:#999;">' +
        '<div style="font-size:28px;margin-bottom:8px;">⏳</div>' +
        '<div style="font-size:13px;">AI 正在分析商品... <span id="aiAnalysisTimer">0s</span></div></div>';
    _aiGenTimer = setInterval(() => {
        _aiGenSeconds++;
        const t = document.getElementById('aiAnalysisTimer');
        if (t) t.textContent = _aiGenSeconds + 's';
    }, 1000);
}

function stopAiLoading() {
    if (_aiGenTimer) { clearInterval(_aiGenTimer); _aiGenTimer = null; }
}

async function autoGeneratePrompts(productDir, forceNew) {
    if (!productDir) return;
    showAiLoading();
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 90000);
    try {
        const resp = await fetch('/api/ai-image/auto-generate-prompts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir, platform: _aiRedrawPlatform, forceNew }),
            signal: controller.signal
        });
        clearTimeout(timeout);
        if (resp.ok) {
            const data = await resp.json();
            if (data.success && data.prompts) {
                // 保存分析维度
                const analysisKeys = ['品类','材质','卖点','目标人群','使用场景','视觉特征'];
                const analysis = {};
                analysisKeys.forEach(k => {
                    if (data.prompts[k]) analysis[k] = data.prompts[k];
                });
                _aiRedrawAnalysis = analysis;
                renderAiAnalysis(analysis);
                // 保存提示词
                if (!_aiRedrawPrompts[_aiRedrawPlatform]) _aiRedrawPrompts[_aiRedrawPlatform] = {};
                for (let i = 1; i <= 5; i++) {
                    const key = '图' + i;
                    if (data.prompts[key]) _aiRedrawPrompts[_aiRedrawPlatform][key] = data.prompts[key];
                }
                renderAiPromptGrid();
                loadWhiteBgImages(productDir);
                loadGeneratedMainImages(productDir);
                saveCurrentToCache();
            }
        }
    } catch (e) {
        console.error('AI 分析失败', e);
        const ag = document.getElementById('aiAnalysisGrid');
        if (ag) ag.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:20px;color:#999;font-size:13px;">⚠️ AI 分析失败: ' + (e.name === 'AbortError' ? '请求超时' : e.message) + '</div>';
    } finally {
        clearTimeout(timeout);
        stopAiLoading();
        renderAiPromptGrid();
        // 如果分析网格还是加载内容，替换掉
        const ag = document.getElementById('aiAnalysisGrid');
        if (ag && ag.textContent.includes('正在分析')) {
            if (Object.keys(_aiRedrawAnalysis).length > 0) {
                renderAiAnalysis(_aiRedrawAnalysis);
            } else {
                ag.innerHTML = '<div style="grid-column:1/-1;text-align:center;padding:20px;color:#ccc;font-size:13px;">分析完成，暂无数据</div>';
            }
        }
    }
}

// 加载已生成的主图（AI重绘图）
async function loadGeneratedMainImages(productDir) {
    if (!productDir) return;
    try {
        const resp = await fetch('/api/ai-image/list-images?productDir=' + encodeURIComponent(productDir));
        if (!resp.ok) return;
        const data = await resp.json();
        (data.images || []).forEach(function(img) {
            const match = img.name.match(/主图_(\d+)_主图\d+\.jpg/);
            if (!match) return;
            const key = '图' + parseInt(match[1]);
            const slot = document.getElementById('aiGenSlot_' + key);
            if (slot) {
                const u = '/api/ai-image/image-file?path=' + encodeURIComponent(img.path);
                // 避免重复添加
                if (slot.querySelector('img[src*="' + encodeURIComponent(img.path) + '"]')) return;
                slot.style.display = 'flex';
                slot.insertAdjacentHTML('beforeend', '<div style="flex-shrink:0;width:120px;height:120px;border-radius:6px;border:1px solid #e0e0e0;overflow:hidden;"><img src="' + u + '&t=' + Date.now() + '" style="width:100%;height:100%;object-fit:cover;cursor:pointer;" onclick="showModal(this.src)"></div>');
            }
        });
    } catch (e) {
        console.error('加载已生成主图失败', e);
    }
}

// 白底图
async function regenerateWhiteBg() {
    if (!_aiRedrawProductDir || _aiWhiteBgLoading) return;
    _aiWhiteBgLoading = true;
    // 在现有网格下方追加加载指示器
    const grid = document.querySelector('.ai-whitebg-grid');
    if (grid) {
        const loadingBar = document.createElement('div');
        loadingBar.id = 'whiteBgLoadingBar';
        loadingBar.style.cssText = 'text-align:center;padding:10px;color:#999;font-size:12px;border-top:1px solid #eee;margin-top:8px;';
        loadingBar.innerHTML = '<span class="spinner" style="display:inline-block;width:16px;height:16px;border-width:2px;vertical-align:middle;"></span> <span id="whiteBgTimer2" style="vertical-align:middle;">正在重新生成...</span>';
        grid.appendChild(loadingBar);
    }
    let _timer = 0;
    const _interval = setInterval(() => {
        _timer++;
        const t = document.getElementById('whiteBgTimer2');
        if (t) t.textContent = '正在重新生成(' + _timer + 's)...';
        if (_timer % 3 === 0) pollWhiteBgImages();
    }, 1000);
    fetch('/api/ai-image/generate-white-bg', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ productDir: _aiRedrawProductDir, force: true })
    }).then(async resp => {
        if (resp.ok) {
            clearInterval(_interval);
            // 从磁盘重新获取白底图列表
            try {
                const listResp = await fetch('/api/ai-image/list-whitebg-images?productDir=' + encodeURIComponent(_aiRedrawProductDir));
                if (listResp.ok) {
                    const listData = await listResp.json();
                    _aiWhiteBgLoading = false;
                    renderWhiteBgGrid(listData.images || []);
                }
            } catch (e) {
                _aiWhiteBgLoading = false;
                renderWhiteBgGrid([]);
            }
            showToast('✅ 白底图已重新生成', 'success');
        }
    }).catch(e => {
        clearInterval(_interval);
        _aiWhiteBgLoading = false;
        showToast('⚠️ 重新生成失败: ' + e.message, 'error');
    });
}

// 轮询白底图目录
async function pollWhiteBgImages() {
    if (!_aiRedrawProductDir) return;
    try {
        const resp = await fetch('/api/ai-image/list-whitebg-images?productDir=' + encodeURIComponent(_aiRedrawProductDir));
        if (!resp.ok) return;
        const data = await resp.json();
        if (data.images && data.images.length > 0) {
            renderWhiteBgGrid(data.images);
        }
    } catch (e) {}
}

function renderWhiteBgGrid(images) {
    const section = document.getElementById('aiAnalysisSection');
    if (!section || !images.length) return;
    // 正在重新生成中时，不渲染（等完成后再替换）
    if (_aiWhiteBgLoading) return;
    document.querySelectorAll('.ai-whitebg-grid').forEach(el => el.remove());
    const galleryUrls = images.map(img => '/api/ai-image/image-file?path=' + encodeURIComponent(img.path));
    let html = '<div class="ai-whitebg-grid" style="margin-top:12px;padding-top:12px;border-top:1px solid #d6e4ff;">';
    html += '<div style="font-size:12px;font-weight:600;color:#1d39c4;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;">';
    html += '<span>⬜ 白底图 <span class="whitebg-count">' + images.length + '/3</span></span>';
    html += '<button class="btn-ai-refresh" onclick="regenerateWhiteBg()" style="font-size:11px;padding:2px 10px;">🔄 重新生成</button>';
    html += '</div><div class="img-container" style="display:flex;gap:8px;flex-wrap:wrap;">';
    images.forEach((img, idx) => {
        const imgUrl = '/api/ai-image/image-file?path=' + encodeURIComponent(img.path);
        html += '<div style="text-align:center;">';
        html += '<img src="' + imgUrl + '&t=' + Date.now() + '" style="width:100px;height:100px;object-fit:cover;border-radius:6px;border:1px solid #e0e0e0;cursor:pointer;" onclick="showModal(\'' + imgUrl + '\')">';
        html += '<div style="font-size:10px;color:#999;margin-top:2px;">' + img.name + '</div>';
        html += '<button onclick="deleteWhiteBgImage(\'' + encodeURIComponent(img.path) + '\')" style="margin-top:4px;padding:2px 8px;border:1px solid #ff4d4f;border-radius:4px;font-size:10px;background:#fff;color:#ff4d4f;cursor:pointer;">🗑️ 删除</button>';
        html += '</div>';
    });
    html += '</div></div>';
    section.insertAdjacentHTML('afterend', html);
}

// 删除单张白底图
async function deleteWhiteBgImage(encodedPath) {
    if (!confirm('确定删除这张白底图吗？')) return;
    const path = decodeURIComponent(encodedPath);
    try {
        const resp = await fetch('/api/ai-image/delete-file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path })
        });
        if (resp.ok) {
            showToast('✅ 白底图已删除', 'success');
            if (_aiRedrawProductDir) {
                const listResp = await fetch('/api/ai-image/list-whitebg-images?productDir=' + encodeURIComponent(_aiRedrawProductDir));
                if (listResp.ok) {
                    const data = await listResp.json();
                    renderWhiteBgGrid(data.images || []);
                }
            }
        } else {
            showToast('❌ 删除失败', 'error');
        }
    } catch (e) {
        showToast('❌ 删除失败: ' + e.message, 'error');
    }
}

let _aiWhiteBgLoading = false;
let _aiWhiteBgTimer = null;
let _aiWhiteBgSeconds = 0;

async function loadWhiteBgImages(productDir) {
    if (!productDir || _aiWhiteBgLoading) return;
    _aiWhiteBgLoading = true;
    const sec = document.getElementById('aiAnalysisSection');
    if (!document.querySelector('.ai-whitebg-grid')) {
        const loadingEl = document.createElement('div');
        loadingEl.className = 'ai-whitebg-grid';
        loadingEl.innerHTML = '<div style="margin-top:12px;padding-top:12px;border-top:1px solid #d6e4ff;"><div style="font-size:12px;font-weight:600;color:#1d39c4;margin-bottom:8px;">⬜ 白底图</div><div style="text-align:center;padding:15px;color:#999;"><span class="spinner" style="display:inline-block;"></span><div style="margin-top:8px;font-size:12px;">正在生成白底图... <span id="whiteBgTimer">0s</span></div></div></div>';
        if (sec) sec.insertAdjacentHTML('afterend', loadingEl.outerHTML);
        _aiWhiteBgSeconds = 0;
        _aiWhiteBgTimer = setInterval(() => {
            _aiWhiteBgSeconds++;
            const t = document.getElementById('whiteBgTimer');
            if (t) t.textContent = _aiWhiteBgSeconds + 's';
        }, 1000);
    }
    try {
        const resp = await fetch('/api/ai-image/generate-white-bg', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir })
        });
        if (!resp.ok) return;
        const data = await resp.json();
        if (!data.success || !data.images || data.images.length === 0) return;
        const analysisSection = document.getElementById('aiAnalysisSection');
        if (!analysisSection) return;
        document.querySelectorAll('.ai-whitebg-grid').forEach(el => el.remove());
        // 构建画廊
        const galleryUrls = data.images.map(img => '/api/ai-image/image-file?path=' + encodeURIComponent(img.path));
        let html = '<div class="ai-whitebg-grid" style="margin-top:12px;padding-top:12px;border-top:1px solid #d6e4ff;">';
        html += '<div style="font-size:12px;font-weight:600;color:#1d39c4;margin-bottom:8px;display:flex;justify-content:space-between;align-items:center;">';
        html += '<span>⬜ 白底图' + (data.fromCache ? ' (已存在)' : ' (AI 生成)') + '</span>';
        html += '<button class="btn-ai-refresh" onclick="regenerateWhiteBg()" style="font-size:11px;padding:2px 10px;">🔄 重新生成</button>';
        html += '</div>';
        html += '<div style="display:flex;gap:8px;flex-wrap:wrap;">';
        data.images.forEach((img, idx) => {
            const imgUrl = galleryUrls[idx];
            html += '<div style="text-align:center;">';
            html += '<img src="' + imgUrl + '" style="width:100px;height:100px;object-fit:cover;border-radius:6px;border:1px solid #e0e0e0;cursor:pointer;" onclick="showModal(\'' + imgUrl + '\',' + JSON.stringify(galleryUrls).replace(/"/g,"'") + ',' + idx + ')">';
            html += '<div style="font-size:10px;color:#999;margin-top:2px;">' + img.name + '</div>';
            html += '<button onclick="deleteWhiteBgImage(\'' + encodeURIComponent(img.path) + '\')" style="margin-top:4px;padding:2px 8px;border:1px solid #ff4d4f;border-radius:4px;font-size:10px;background:#fff;color:#ff4d4f;cursor:pointer;">🗑️ 删除</button>';
            html += '</div>';
        });
        html += '</div></div>';
        analysisSection.insertAdjacentHTML('afterend', html);
        showToast('✅ 白底图' + (data.fromCache ? '已存在' : 'AI 生成完成'), 'success');
    } catch (e) {
        console.error('加载白底图失败', e);
        showToast('⚠️ 白底图获取失败: ' + e.message, 'error');
    } finally {
        _aiWhiteBgLoading = false;
        if (_aiWhiteBgTimer) { clearInterval(_aiWhiteBgTimer); _aiWhiteBgTimer = null; }
    }
}

function renderAiAnalysis(analysis) {
    const section = document.getElementById('aiAnalysisSection');
    const grid = document.getElementById('aiAnalysisGrid');
    if (!section || !grid) return;
    const keys = Object.keys(analysis);
    if (keys.length === 0) return;
    let html = '';
    for (const k of keys) {
        html += '<div class="ai-analysis-item"><div class="label">' + k + '</div><div>' + escapeHtml(analysis[k]) + '</div></div>';
    }
    grid.innerHTML = html;
    section.style.display = 'block';
}

async function refreshAiAnalysis() {
    if (!_aiRedrawProductDir) return;
    autoGeneratePrompts(_aiRedrawProductDir, true);
}

// 生成图片
async function generateAiImages() {
    const btn = document.getElementById('aiGenerateBtn');
    const status = document.getElementById('aiGenerateStatus');
    const model = document.getElementById('aiModelSelect').value;
    const prompts = {};
    const checkedKeys = [];
    document.querySelectorAll('#aiPromptGrid input[data-check-key]').forEach(cb => {
        if (cb.checked) {
            const key = cb.dataset.checkKey;
            const ta = document.querySelector('textarea[data-key="' + key + '"]');
            if (ta) {
                const val = ta.value.trim();
                if (val) { prompts[key] = val; checkedKeys.push(key); }
            }
        }
    });
    const keys = checkedKeys;
    if (keys.length === 0) {
        status.textContent = '⚠️ 请填写提示词';
        return;
    }
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner"></span> 生成中...';
    status.textContent = '正在生成...';
    // 生成前：在画廊右侧追加加载占位框
    const loadKeys = {};
    keys.forEach(key => {
        const slot = document.getElementById('aiGenSlot_' + key);
        if (slot) {
            slot.style.display = 'flex';
            const loadHtml = '<div class="ai-gen-loading" style="flex-shrink:0;width:120px;height:120px;border-radius:6px;border:2px dashed #667eea;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;background:#f0f2ff;">'
                + '<span class="spinner" style="display:inline-block;width:20px;height:20px;border-width:2px;border-top-color:#667eea;"></span>'
                + '<span style="font-size:11px;color:#667eea;">生成中...</span></div>';
            slot.insertAdjacentHTML('beforeend', loadHtml);
            loadKeys[key] = true;
        }
    });
    try {
        const resp = await fetch('/api/ai-image/generate-all', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                model, prompt: keys.map(k => k + '：' + prompts[k]).join('\n'),
                allPrompts: prompts, n: keys.length,
                productDir: _aiRedrawProductDir, platform: _aiRedrawPlatform
            })
        });
        if (resp.ok) {
            const data = await resp.json();
            status.textContent = '✅ ' + (data.succeeded || 0) + '/' + (data.total || 0) + ' 成功';
            if (data.results && data.results.length > 0) {
                data.results.forEach(function(r) {
                    const slot = document.getElementById('aiGenSlot_' + r.key);
                    if (slot) {
                        const u = '/api/ai-image/image-file?path=' + encodeURIComponent(r.path);
                        // 替换加载占位框为实际图片
                        const loadingEl = slot.querySelector('.ai-gen-loading');
                        if (loadingEl) {
                            loadingEl.outerHTML = '<div style="flex-shrink:0;width:120px;height:120px;border-radius:6px;border:1px solid #e0e0e0;overflow:hidden;"><img src="' + u + '&t=' + Date.now() + '" style="width:100%;height:100%;object-fit:cover;cursor:pointer;" onclick="showModal(this.src)"></div>';
                        } else {
                            slot.insertAdjacentHTML('beforeend', '<div style="flex-shrink:0;width:120px;height:120px;border-radius:6px;border:1px solid #e0e0e0;overflow:hidden;"><img src="' + u + '&t=' + Date.now() + '" style="width:100%;height:100%;object-fit:cover;cursor:pointer;" onclick="showModal(this.src)"></div>');
                        }
                    }
                });
            }
            // 失败的项移除加载占位框
            if (data.errors && data.errors.length > 0) {
                data.errors.forEach(function(e) {
                    const slot = document.getElementById('aiGenSlot_' + e.key);
                    if (slot) {
                        const loadingEl = slot.querySelector('.ai-gen-loading');
                        if (loadingEl) loadingEl.remove();
                    }
                });
            }
        }
    } catch (e) {
        status.textContent = '❌ ' + e.message;
        // 请求整体失败，移除所有加载占位框
        document.querySelectorAll('.ai-gen-loading').forEach(el => el.remove());
    }
    btn.disabled = false;
    btn.textContent = '🚀 生成全部勾选的主图';
}

// 替换图函数
let _replacePlaceholderEl = null;

// 从文件加载已有的替换结果
async function loadExistingReplaceResults(productDir) {
    if (!productDir) return;
    try {
        const resp = await fetch('/api/ai-image/list-replace-images?productDir=' + encodeURIComponent(productDir));
        if (!resp.ok) return;
        const data = await resp.json();
        if (!data.success || !data.images || data.images.length === 0) return;
        _aiReplaceResults = data.images.map((img, i) => ({
            key: '替换图' + (i + 1),
            path: img.path,
            success: true
        }));
        showReplaceResults();
    } catch (e) {
        console.error('加载已有替换结果失败', e);
    }
}

function openReplaceUpload(el) {
    _replacePlaceholderEl = el;
    const btn = document.getElementById('uploadConfirmBtn');
    btn._originalOnclick = btn.onclick; // 保存原始 onclick
    document.getElementById('uploadModalTitle').textContent = '添加替换图';
    document.getElementById('uploadFileInput').value = '';
    document.getElementById('uploadUrlInput').value = '';
    btn.disabled = false;
    if (typeof switchUploadTab === 'function') switchUploadTab('local');
    document.getElementById('uploadModal').classList.add('active');
    btn.onclick = confirmReplaceUpload;
}

async function confirmReplaceUpload() {
    const fileInput = document.getElementById('uploadFileInput');
    const urlInput = document.getElementById('uploadUrlInput');
    let imageData = null;

    if (fileInput.files && fileInput.files[0]) {
        const file = fileInput.files[0];
        imageData = await new Promise(resolve => {
            const reader = new FileReader();
            reader.onload = e => resolve(e.target.result);
            reader.readAsDataURL(file);
        });
    } else if (urlInput.value.trim()) {
        imageData = urlInput.value.trim();
    }

    if (!imageData) { showToast('请选择文件或填写URL', 'error'); return; }

    _aiReplaceImages.push(imageData);
    _aiReplacePrompts.push('');
    renderReplaceGrid();
    // 恢复确认按钮原始 onclick
    const btn = document.getElementById('uploadConfirmBtn');
    if (btn && btn._originalOnclick) btn.onclick = btn._originalOnclick;
    closeUploadModal();
    showToast('✅ 已添加替换图', 'success');
}

function renderReplaceGrid() {
    const grid = document.getElementById('aiReplaceGrid');
    if (!grid) return;
    let html = '';
    _aiReplaceImages.forEach((img, idx) => {
        const src = img.startsWith('data:') || img.startsWith('http://') || img.startsWith('https://') ? img : '/api/ai-image/image-file?path=' + encodeURIComponent(img);
        html += '<div class="img-item" data-filepath="" style="margin-bottom:8px;display:flex;flex-direction:column;align-items:center;">';
        html += '<div class="img-wrap"><img draggable="true" src="' + src + '" onclick="showModal(this.src)">';
        html += '<div class="img-label">' + (idx + 1) + '</div></div>';
        html += '<div class="img-actions-bar"><button class="action-btn btn-delete" onclick="removeReplaceImage(' + idx + ')">删除</button></div>';
        html += '<input type="text" class="replace-prompt-input" data-idx="' + idx + '" placeholder="必填：输入替换要求（如：替换模特手上的物件）" value="' + escapeHtml(_aiReplacePrompts[idx] || '') + '" style="width:100%;margin-top:4px;padding:6px 8px;border:1px solid #ff4d4f;border-radius:4px;font-size:11px;outline:none;" onchange="updateReplacePrompt(' + idx + ', this.value)">';
        // AI 生成结果 slot
        html += '<div class="replace-gen-slot" id="replaceGenSlot_' + idx + '" style="margin-top:6px;width:120px;min-height:100px;border:1px dashed #d9d9d9;border-radius:6px;display:flex;flex-direction:column;align-items:center;justify-content:center;color:#ccc;font-size:11px;background:#fafafa;">';
        html += '<span style="font-size:10px;">结果图</span></div>';
        html += '</div>';
    });
    // 保留原有的权重
    if (_aiReplaceImages.length === 0) {
        html += '<div class="img-item"><div class="img-placeholder" onclick="openReplaceUpload(this)">';
        html += '<div class="placeholder-icon">+</div><div class="placeholder-text">添加图</div></div></div>';
    } else {
        html += '<div class="img-item" style="display:flex;flex-direction:column;align-items:center;justify-content:center;"><div class="img-placeholder" onclick="openReplaceUpload(this)">';
        html += '<div class="placeholder-icon">+</div><div class="placeholder-text">添加图</div></div></div>';
    }
    grid.innerHTML = html;
    // 渲染已有结果
    _aiReplaceResults.forEach((r, ri) => {
        const slot = document.getElementById('replaceGenSlot_' + ri);
        if (!slot) return;
        const u = '/api/ai-image/image-file?path=' + encodeURIComponent(r.path);
        slot.style.border = '1px solid #e0e0e0';
        slot.style.background = '#fff';
        slot.style.justifyContent = 'flex-start';
        slot.innerHTML = ''
            + '<img src="' + u + '&t=' + Date.now() + '" style="width:120px;height:100px;object-fit:cover;border-radius:4px;cursor:pointer;" onclick="showModal(this.src)">'
            + '<div style="font-size:10px;color:#999;margin-top:2px;">' + r.key + '</div>'
            + '<div style="margin-top:4px;display:flex;gap:4px;">'
            + '<button onclick="regenerateReplaceImage(' + ri + ')" style="padding:2px 6px;border:1px solid #667eea;border-radius:4px;font-size:10px;background:#fff;color:#667eea;cursor:pointer;">🔄</button>'
            + '<button onclick="deleteReplaceImage(' + ri + ',\'' + r.path + '\')" style="padding:2px 6px;border:1px solid #ff4d4f;border-radius:4px;font-size:10px;background:#fff;color:#ff4d4f;cursor:pointer;">🗑️</button>'
            + '</div>';
    });
}

function updateReplacePrompt(idx, val) {
    _aiReplacePrompts[idx] = val;
}

function removeReplaceImage(idx) {
    _aiReplaceImages.splice(idx, 1);
    _aiReplacePrompts.splice(idx, 1);
    renderReplaceGrid();
}

async function generateReplaceImages() {
    if (_aiReplaceImages.length === 0) { showToast('⚠️ 请先添加图片', 'error'); return; }
    if (!_aiRedrawProductDir) { showToast('⚠️ 缺少商品目录', 'error'); return; }
    // 校验追加提示词是否全部填写
    const emptyPrompts = _aiReplacePrompts.some(p => !p || !p.trim());
    if (emptyPrompts) { showToast('⚠️ 请填写所有场景图的追加提示词（必填）', 'error'); return; }
    // 检查白底图是否存在（DOM 中有白底图网格即存在）
    if (!document.querySelector('.ai-whitebg-grid')) {
        showToast('⚠️ 请先生成白底图（点击刷新生成）', 'error');
        return;
    }
    const btn = document.getElementById('aiReplaceBtn');
    const status = document.getElementById('aiReplaceStatus');
    btn.disabled = true; btn.innerHTML = '<span class="spinner"></span> 生成中...';
    status.textContent = '正在生成...';
    try {
        const resp = await fetch('/api/ai-image/replace', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ productDir: _aiRedrawProductDir, images: _aiReplaceImages, prompts: _aiReplacePrompts, model: document.getElementById('aiReplaceModelSelect').value })
        });
        if (resp.ok) {
            const data = await resp.json();
            status.textContent = '✅ ' + (data.succeeded || 0) + ' 成功, ❌ ' + (data.failed || 0) + ' 失败';
            // 显示预览
            if (data.results && data.results.length > 0) {
                _aiReplaceResults = data.results;
                showReplaceResults();
            }
        } else {
            status.textContent = '❌ 请求失败';
        }
    } catch (e) {
        status.textContent = '❌ ' + e.message;
    }
    btn.disabled = false; btn.textContent = '🚀 生成替换图';
}

// 重新生成单张替换图
async function regenerateReplaceImage(idx) {
    if (!_aiReplaceImages[idx]) { showToast('⚠️ 缺少原图数据', 'error'); return; }
    showToast('⏳ 重新生成第' + (idx + 1) + '张...', 'info');
    try {
        const resp = await fetch('/api/ai-image/replace', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                productDir: _aiRedrawProductDir,
                images: [_aiReplaceImages[idx]],
                prompts: [_aiReplacePrompts[idx] || '""'],
                model: document.getElementById('aiReplaceModelSelect').value
            })
        });
        if (resp.ok) {
            const data = await resp.json();
            if (data.results && data.results.length > 0) {
                _aiReplaceResults[idx] = data.results[0];
                showReplaceResults();
                showToast('✅ 第' + (idx + 1) + '张已重新生成', 'success');
            }
        }
    } catch (e) {
        showToast('❌ ' + e.message, 'error');
    }
}

function showReplaceResults() {
    // 将生成结果填入每张图下方的 replaceGenSlot 中
    _aiReplaceResults.forEach((r, ri) => {
        const slot = document.getElementById('replaceGenSlot_' + ri);
        if (!slot) return;
        const u = '/api/ai-image/image-file?path=' + encodeURIComponent(r.path);
        slot.style.border = '1px solid #e0e0e0';
        slot.style.background = '#fff';
        slot.style.justifyContent = 'flex-start';
        slot.innerHTML = ''
            + '<img src="' + u + '&t=' + Date.now() + '" style="width:120px;height:100px;object-fit:cover;border-radius:4px;cursor:pointer;" onclick="showModal(this.src)">'
            + '<div style="font-size:10px;color:#999;margin-top:2px;">' + r.key + '</div>'
            + '<div style="margin-top:4px;display:flex;gap:4px;">'
            + '<button onclick="regenerateReplaceImage(' + ri + ')" style="padding:2px 6px;border:1px solid #667eea;border-radius:4px;font-size:10px;background:#fff;color:#667eea;cursor:pointer;">🔄</button>'
            + '<button onclick="deleteReplaceImage(' + ri + ',\'' + r.path + '\')" style="padding:2px 6px;border:1px solid #ff4d4f;border-radius:4px;font-size:10px;background:#fff;color:#ff4d4f;cursor:pointer;">🗑️</button>'
            + '</div>';
    });
}

// 删除单张替换图
async function deleteReplaceImage(idx, filePath) {
    if (!confirm('确定删除该替换图吗？')) return;
    try {
        const resp = await fetch('/api/ai-image/delete-file', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: filePath })
        });
        if (resp.ok) {
            const result = await resp.json();
            if (result.success) {
                _aiReplaceResults.splice(idx, 1);
                showReplaceResults();
                saveCurrentToCache();
                showToast('✅ 已删除', 'success');
            } else {
                showToast('❌ 删除失败: ' + (result.error || '文件不存在'), 'error');
            }
        } else {
            showToast('❌ 删除失败', 'error');
        }
    } catch (e) {
        showToast('❌ ' + e.message, 'error');
    }
}

// 标签切换
document.addEventListener('click', function(e) {
    const tabBtn = e.target.closest('#aiTabBar .ai-tab');
    if (!tabBtn) return;
    const tab = tabBtn.dataset.tab;
    document.querySelectorAll('#aiTabBar .ai-tab').forEach(b => b.classList.remove('active'));
    tabBtn.classList.add('active');
    document.querySelectorAll('.ai-tab-content').forEach(el => el.style.display = 'none');
    const target = document.getElementById('aiTab' + tab.charAt(0).toUpperCase() + tab.slice(1));
    if (target) target.style.display = 'block';
});

// 平台切换
document.addEventListener('click', function(e) {
    const platBtn = e.target.closest('#aiPlatformSelect button');
    if (!platBtn) return;
    document.querySelectorAll('#aiPlatformSelect button').forEach(b => b.classList.remove('active'));
    platBtn.classList.add('active');
    _aiRedrawPlatform = platBtn.dataset.platform;
    renderAiPromptGrid();
    // 加载该平台已有 AI 图
    if (_aiRedrawProductDir) loadGeneratedMainImages(_aiRedrawProductDir);
});

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
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
            if (typeof _sidebarIndex !== 'undefined') _sidebarIndex = 0;
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
