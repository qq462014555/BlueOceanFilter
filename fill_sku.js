const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const SIGNAL_DIR = path.join(__dirname, 'signal');
const MAX_RETRIES = 3;

// ============================================================
//  策略注册表 —— 新增 SKU 填写规则只需在此注册
//  每个策略: { name, priority, match(pageState, aiResult), execute(page, pageState, aiResult, attrOptions) }
//  match 返回 { ok: boolean, reason?: string }
//  execute 返回 { success: boolean, reason?: string }
// ============================================================
const STRATEGIES = [];
function registerStrategy(s) { STRATEGIES.push(s); }

// ---------- 策略 1: 已有属性区域直接填写 ----------
registerStrategy({
  name: 'fillExistingAttrs',
  priority: 10,
  match: (pageState) => ({ ok: pageState.hasExistingProps }),
  async execute(page, pageState, aiResult, attrOptions) {
    console.log(`[sku] 策略: 已有属性区域直接填写`);
    const success = await doFillExistingAttrs(page, attrOptions, pageState.existingAttrs);
    return { success, reason: success ? undefined : '填写失败' };
  }
});

// ---------- 策略 2: 预置属性按钮模式 ----------
registerStrategy({
  name: 'presetAttr',
  priority: 20,
  match: (pageState) => ({ ok: pageState.hasPresetController }),
  async execute(page, pageState, aiResult, attrOptions) {
    console.log(`[sku] 策略: 预置属性按钮模式 (${pageState.controllerBtns.join(', ')})`);
    const success = await doPresetFill(page, attrOptions, pageState.controllerBtns);
    return { success, reason: success ? undefined : '预置填写失败' };
  }
});

// ---------- 策略 3: 创建规格模式 ----------
registerStrategy({
  name: 'createSpec',
  priority: 30,
  match: (pageState) => ({ ok: pageState.hasCreateBtn }),
  async execute(page, pageState, aiResult, attrOptions) {
    console.log(`[sku] 策略: 创建规格模式`);
    const success = await doCreateSpecAndFill(page, attrOptions);
    return { success, reason: success ? undefined : '创建规格失败' };
  }
});

// 按 priority 升序（数字越小越先尝试）
STRATEGIES.sort((a, b) => a.priority - b.priority);


async function fillSku() {
  const files = fs.readdirSync(SIGNAL_DIR)
    .filter(f => f.startsWith('fill_sku_') && f.endsWith('.json'))
    .map(f => path.join(SIGNAL_DIR, f))
    .sort((a, b) => fs.statSync(a).mtimeMs - fs.statSync(b).mtimeMs);

  if (files.length === 0) {
    console.log('[sku] 无新信号');
    return;
  }

  const signalFile = files[0];
  let signal;
  try {
    signal = JSON.parse(fs.readFileSync(signalFile, 'utf-8'));
  } catch (e) {
    console.log(`[sku] 信号文件JSON解析失败，删除: ${e.message}`);
    fs.unlinkSync(signalFile);
    return;
  }

  const productDir = signal.productDir;
  if (!productDir) {
    console.log('[sku] 信号文件缺少 productDir，删除');
    fs.unlinkSync(signalFile);
    return;
  }

  const aiResultPath = path.join(productDir, 'sku-ai-result.json');
  if (!fs.existsSync(aiResultPath)) {
    console.log(`[sku] AI 结果文件不存在: ${aiResultPath}`);
    fs.unlinkSync(signalFile);
    return;
  }

  console.log(`[sku] 处理信号: ${path.basename(signalFile)}`);

  let aiResult;
  try {
    aiResult = JSON.parse(fs.readFileSync(aiResultPath, 'utf-8'));
  } catch (e) {
    console.log(`[sku] AI 结果JSON解析失败: ${e.message}`);
    fs.unlinkSync(signalFile);
    return;
  }

  // === 模式判断 ===
  const mode = aiResult.mode || determineMode(aiResult);
  console.log(`[sku] 模式: ${mode}`);

  // === 提取属性选项 ===
  let attrOptions;
  let firstAttrName; // single 模式用

  if (mode === 'multi') {
    const multi = aiResult.multi;
    const levels = multi.levels || [];
    attrOptions = {};
    for (const level of levels) {
      if (level.name && level.options) {
        attrOptions[level.name] = level.options.filter(o => o && o.trim());
      }
    }
    console.log(`[sku] multi: ${Object.keys(attrOptions).length} 个属性`);
    for (const [name, opts] of Object.entries(attrOptions)) {
      console.log(`[sku]   ${name}: [${opts.join(', ')}]`);
    }
  } else {
    const single = aiResult.single || [];
    if (single.length === 0) {
      console.log('[sku] single 为空');
      fs.unlinkSync(signalFile);
      return;
    }
    console.log(`[sku] single: ${single.length} 个SKU`);

    // 连接 Chrome 检测页面第一个属性名
    console.log('[sku] 连接 Chrome CDP 9223...');
    const playwright = await chromium.connectOverCDP('http://127.0.0.1:9223');
    const context = playwright.contexts()[0];
    let skuPage = null;
    for (const p of context.pages()) {
      try { if ((await p.title()).includes('商品发布')) { skuPage = p; break; } } catch (e) {}
    }
    if (!skuPage) { console.log('[sku] 未找到商品发布页面'); process.exit(1); }
    await skuPage.bringToFront();
    await skuPage.waitForTimeout(500);

    firstAttrName = await skuPage.evaluate(() => {
      const saleProps = document.querySelector('.sell-component-sale-props');
      if (saleProps) {
        const label = saleProps.querySelector('.props-label');
        if (label) return label.textContent.trim().replace(/\(0\)/g, '').trim();
      }
      const propItem = document.querySelector('.multi-sale-props .prop-item.selected span');
      if (propItem) return propItem.textContent.trim();
      return '颜色分类';
    });

    console.log(`[sku] 页面属性名: ${firstAttrName}`);
    attrOptions = {};
    attrOptions[firstAttrName] = single.map(s => s.name).filter(n => n && n.trim());

    // 直接填写（single 模式不需要策略调度，直接操作）
    console.log(`[sku] 开始填写 ${attrOptions[firstAttrName].length} 个SKU名称...`);
    await fillSingleMode(skuPage, firstAttrName, attrOptions[firstAttrName]);

    const screenshotName = `sku_${Date.now()}.png`;
    const screenshotPath = path.join(SIGNAL_DIR, screenshotName);
    const screenshot = await skuPage.screenshot({ fullPage: true });
    fs.writeFileSync(screenshotPath, screenshot);
    console.log(`[sku] 截图: ${screenshotPath}`);

    await playwright.close();
    fs.unlinkSync(signalFile);
    console.log(`[sku] 已删除信号文件: ${path.basename(signalFile)}`);
    return;
  }

  // === multi 模式：策略调度 ===
  console.log('[sku] 连接 Chrome CDP 9223...');
  const playwright = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const context = playwright.contexts()[0];
  let page = null;
  for (const p of context.pages()) {
    try { if ((await p.title()).includes('商品发布')) { page = p; break; } } catch (e) {}
  }
  if (!page) { console.log('[sku] 未找到商品发布页面'); process.exit(1); }

  await page.bringToFront();
  await page.waitForTimeout(500);

  // 检测页面状态
  const pageState = await detectPageState(page);
  console.log(`[sku] 页面状态: ${JSON.stringify(pageState)}`);

  // 智能匹配 AI 属性名 → 页面实际属性名
  const allPageNames = [...new Set([...pageState.existingAttrs, ...pageState.controllerBtns])];
  const attrNameMap = matchAttrNames(Object.keys(attrOptions), allPageNames);
  console.log(`[sku] 属性名匹配: ${JSON.stringify(attrNameMap)}`);

  // 用页面实际名称替换 key
  const pageAttrOptions = {};
  for (const [aiName, opts] of Object.entries(attrOptions)) {
    const pageName = attrNameMap[aiName] || aiName;
    pageAttrOptions[pageName] = opts;
  }
  console.log(`[sku] 页面属性: ${Object.keys(pageAttrOptions).join(', ')}`);

  // === 策略调度 ===
  // 1. 检查是否有 AI 指定的策略
  const preferredStrategy = aiResult.strategy;
  let success = false;

  if (preferredStrategy) {
    const strat = STRATEGIES.find(s => s.name === preferredStrategy);
    if (strat) {
      const m = strat.match(pageState);
      if (m.ok) {
        console.log(`[sku] AI 指定策略: ${strat.name}`);
        const result = await strat.execute(page, pageState, aiResult, pageAttrOptions);
        success = result.success;
        if (!success) console.log(`[sku] AI 策略失败: ${result.reason}`);
      } else {
        console.log(`[sku] AI 策略 ${preferredStrategy} 不适用: ${m.reason}`);
      }
    } else {
      console.log(`[sku] 未知策略: ${preferredStrategy}`);
    }
  }

  // 2. 如果 AI 未指定或失败，按优先级尝试所有匹配的策略
  if (!success) {
    for (const strat of STRATEGIES) {
      if (strat.name === preferredStrategy) continue; // 已尝试过
      const m = strat.match(pageState);
      if (!m.ok) {
        console.log(`[sku] 跳过策略 ${strat.name}: ${m.reason}`);
        continue;
      }
      console.log(`[sku] 尝试策略: ${strat.name}`);
      const result = await strat.execute(page, pageState, aiResult, pageAttrOptions);
      if (result.success) { success = true; break; }
      console.log(`[sku] 策略失败: ${result.reason}`);
    }
  }

  if (!success) console.log('[sku] 所有策略均失败');

  const screenshotName = `sku_${Date.now()}.png`;
  const screenshotPath = path.join(SIGNAL_DIR, screenshotName);
  const screenshot = await page.screenshot({ fullPage: true });
  fs.writeFileSync(screenshotPath, screenshot);
  console.log(`[sku] 截图: ${screenshotPath}`);

  await playwright.close();
  fs.unlinkSync(signalFile);
  console.log(`[sku] 已删除信号文件: ${path.basename(signalFile)}`);
}


// ============================================================
//  页面状态检测（独立函数，方便策略调用）
// ============================================================
async function detectPageState(page) {
  return await page.evaluate(() => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    const hasExistingProps = saleProps && saleProps.querySelectorAll('.common-wrap').length > 0;

    const xpath = "//*[contains(text(),'创建规格')]";
    const createBtn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;

    const controller = document.querySelector('#sell-field-customOptionalSaleProp .sell-component-custom-sale-props-controller');
    const controllerBtns = controller ? Array.from(controller.querySelectorAll('.next-btn-helper')).map(h => h.textContent.trim()) : [];

    const existingAttrs = [];
    if (hasExistingProps) {
      const labels = saleProps.querySelectorAll('.props-label');
      for (const label of labels) {
        const text = label.textContent.trim().replace(/\(0\)/g, '').trim();
        if (text) existingAttrs.push(text);
      }
    }

    return {
      hasExistingProps,
      existingAttrs,
      hasCreateBtn: !!createBtn,
      hasPresetController: !!controller,
      controllerBtns,
    };
  });
}


// ============================================================
//  属性名模糊匹配
// ============================================================
function matchAttrNames(aiNames, pageNames) {
  const map = {};
  const used = new Set();

  for (const aiName of aiNames) {
    let matched = null;

    // 1. 精确匹配
    for (const pn of pageNames) {
      if (pn === aiName && !used.has(pn)) { matched = pn; break; }
    }
    if (matched) { map[aiName] = matched; used.add(matched); continue; }

    // 2. AI 名包含在页面名中
    for (const pn of pageNames) {
      if (!used.has(pn) && pn.includes(aiName)) { matched = pn; break; }
    }
    if (matched) { map[aiName] = matched; used.add(matched); continue; }

    // 3. 页面名包含在 AI 名中
    for (const pn of pageNames) {
      if (!used.has(pn) && aiName.includes(pn)) { matched = pn; break; }
    }
    if (matched) { map[aiName] = matched; used.add(matched); continue; }

    // 4. 去掉括号/空格后匹配
    const aiClean = aiName.replace(/[\(\)\s（）]/g, '');
    for (const pn of pageNames) {
      if (!used.has(pn)) {
        const pnClean = pn.replace(/[\(\)\s（）]/g, '');
        if (pnClean.includes(aiClean) || aiClean.includes(pnClean)) { matched = pn; break; }
      }
    }
    if (matched) { map[aiName] = matched; used.add(matched); continue; }

    map[aiName] = aiName;
  }

  return map;
}


// ============================================================
//  模式判断（兜底）
// ============================================================
function determineMode(aiResult) {
  try {
    const multi = aiResult.multi;
    if (!multi) return 'single';
    const levels = multi.levels;
    const skus = multi.skus;
    if (!levels || !skus || skus.length === 0) return 'single';
    if (levels.length <= 1) return 'single';
    let fullCartesian = 1;
    for (const level of levels) {
      fullCartesian *= (level.options || []).length;
    }
    if (skus.length > fullCartesian) return 'single';
    const ratio = skus.length / fullCartesian;
    return ratio >= 0.8 ? 'multi' : 'single';
  } catch (e) {
    return 'single';
  }
}


// ============================================================
//  策略实现：已有属性区域直接填写
// ============================================================
async function doFillExistingAttrs(page, attrOptions, existingAttrs) {
  try {
    const missingAttrs = Object.keys(attrOptions).filter(a => !existingAttrs.includes(a));
    if (missingAttrs.length > 0) {
      console.log(`[sku] 缺失属性: ${missingAttrs.join(', ')}`);
    }

    const pageOrder = await page.evaluate(() => {
      const saleProps = document.querySelector('.sell-component-sale-props');
      if (!saleProps) return [];
      const labels = saleProps.querySelectorAll('.props-label');
      const names = [];
      for (const label of labels) {
        const text = label.textContent.trim().replace(/\(0\)/g, '').trim();
        if (text) names.push(text);
      }
      return names;
    });

    console.log(`[sku] 页面属性顺序: ${pageOrder.join(', ')}`);

    for (const attrName of pageOrder) {
      const options = attrOptions[attrName];
      if (!options || options.length === 0) continue;
      console.log(`[sku] 填写 [${attrName}] ${options.length} 个选项`);
      await fillAttrOptions(page, attrName, options);
    }

    return true;
  } catch (e) {
    console.log(`[sku] 填写异常: ${e.message}`);
    return false;
  }
}


// ============================================================
//  策略实现：创建规格模式
// ============================================================
async function doCreateSpecAndFill(page, attrOptions) {
  try {
    const attrNames = Object.keys(attrOptions);

    // 1. 点"+ 创建规格"
    console.log('[sku] 点击"+ 创建规格"...');
    const createBtnFound = await page.evaluate(() => {
      const xpath = "//*[contains(text(),'创建规格')]";
      const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
      if (!btn) return false;
      btn.scrollIntoView({ block: 'center' });
      btn.click();
      return true;
    });
    if (!createBtnFound) { console.log('[sku] 未找到"创建规格"按钮'); return false; }
    await page.waitForTimeout(1500);

    // 2. 选"选择标准属性构建规格"
    console.log('[sku] 选择"选择标准属性构建规格"...');
    await page.evaluate(() => {
      const radios = document.querySelectorAll('.next-radio-wrapper');
      for (const radio of radios) {
        const label = radio.querySelector('.next-radio-label');
        if (label && label.textContent.trim().includes('选择标准属性构建规格')) {
          radio.scrollIntoView({ block: 'center' });
          radio.click();
          return true;
        }
      }
      return false;
    });
    await page.waitForTimeout(1000);

    // 3. 勾选属性
    console.log('[sku] 勾选属性...');
    await page.evaluate((attrNames) => {
      const propItems = document.querySelectorAll('.multi-sale-props .prop-item');
      for (const item of propItems) {
        const span = item.querySelector('span');
        if (!span) continue;
        const name = span.textContent.trim();
        if (attrNames.includes(name)) {
          const cb = item.querySelector('.next-checkbox input, input[type="checkbox"]');
          if (!cb || !cb.checked) item.click();
        }
      }
    }, attrNames);
    await page.waitForTimeout(500);

    // 4. 点确定
    console.log('[sku] 点击确认...');
    const confirmClicked = await page.evaluate(() => {
      const dialog = document.querySelector('.next-dialog, [role="dialog"]');
      if (!dialog) return false;
      const btns = dialog.querySelectorAll('.next-btn, .dialog-footer button');
      for (const btn of btns) {
        const text = btn.textContent.trim();
        if (text.includes('确定') || text.includes('确认') || text.includes('完成')) {
          btn.click();
          return true;
        }
      }
      return false;
    });
    if (confirmClicked) await page.waitForTimeout(1500);

    // 5. 填写选项值
    return await doFillExistingAttrs(page, attrOptions, []);
  } catch (e) {
    console.log(`[sku] 创建规格异常: ${e.message}`);
    return false;
  }
}


// ============================================================
//  策略实现：预置属性按钮模式
// ============================================================
async function doPresetFill(page, attrOptions, controllerBtns) {
  try {
    const attrNames = Object.keys(attrOptions);
    for (const attrName of attrNames) {
      const clicked = await page.evaluate((attrName) => {
        const container = document.querySelector('#sell-field-customOptionalSaleProp .sell-component-custom-sale-props-controller');
        if (!container) return false;
        const buttons = container.querySelectorAll('.sell-component-custom-sale-props-controller-item');
        for (const btn of buttons) {
          const helper = btn.querySelector('.next-btn-helper');
          if (helper && helper.textContent.trim() === attrName) {
            btn.click();
            return true;
          }
        }
        return false;
      }, attrName);
      if (clicked) console.log(`[sku] 已点击属性: ${attrName}`);
      await page.waitForTimeout(800);
    }
    await page.waitForTimeout(1000);
    return await doFillExistingAttrs(page, attrOptions, []);
  } catch (e) {
    console.log(`[sku] 预置模式异常: ${e.message}`);
    return false;
  }
}


// ============================================================
//  Single 模式填写（独立商品名 → 第一个属性）
// ============================================================
async function fillSingleMode(page, attrName, options) {
  const inputType = await page.evaluate((attrName) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return 'text';
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        if (wrap.querySelector('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input')) return 'text';
        if (wrap.querySelector('.next-select-inner, .next-select')) return 'dropdown';
        return 'text';
      }
    }
    return 'text';
  }, attrName);

  console.log(`[sku] single 输入类型: ${inputType}`);

  if (inputType === 'text') {
    for (let i = 0; i < options.length; i++) {
      await fillSingleOption(page, attrName, options[i]);
      if (i < options.length - 1) await clickAddButton(page, attrName);
      await page.waitForTimeout(300);
    }
  } else if (inputType === 'dropdown') {
    for (let i = 0; i < options.length; i++) {
      await selectDropdownOption(page, attrName, options[i]);
      if (i < options.length - 1) await clickAddButton(page, attrName);
      await page.waitForTimeout(500);
    }
  }
}


// ============================================================
//  通用：填写属性选项
// ============================================================
async function fillAttrOptions(page, attrName, options) {
  const inputType = await page.evaluate((attrName) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return 'unknown';
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        if (wrap.querySelector('.sell-color-item-container input, .sale-props-auto-crop-pic-common-wrap input')) return 'text';
        if (wrap.querySelector('.next-select-inner, .next-select')) return 'dropdown';
        return 'unknown';
      }
    }
    return 'unknown';
  }, attrName);

  console.log(`[sku]   类型: ${inputType}`);

  if (inputType === 'text') {
    for (let i = 0; i < options.length; i++) {
      await fillSingleOption(page, attrName, options[i]);
      if (i < options.length - 1) await clickAddButton(page, attrName);
      await page.waitForTimeout(300);
    }
  } else if (inputType === 'dropdown') {
    for (let i = 0; i < options.length; i++) {
      await selectDropdownOption(page, attrName, options[i]);
      if (i < options.length - 1) await clickAddButton(page, attrName);
      await page.waitForTimeout(500);
    }
  }
}

async function fillSingleOption(page, attrName, value) {
  const debug = await page.evaluate(({ attrName }) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return { found: false, reason: 'no saleProps' };
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        // 只找颜色分类容器内的输入框，按 placeholder 区分主色和备注
        const container = wrap.querySelector('.sell-color-item-container');
        if (!container) return { found: false, reason: 'no container' };
        const inputs = container.querySelectorAll('input');
        const colorInputs = Array.from(inputs).filter(inp => inp.placeholder === '主色(必选)');
        const info = colorInputs.map((inp, i) => ({ idx: i, value: inp.value.substring(0, 15), empty: !inp.value || inp.value.trim() === '' }));
        return { found: true, totalInputs: colorInputs.length, info };
      }
    }
    return { found: false, reason: 'no matching wrap' };
  }, { attrName });

  if (!debug.found) {
    console.log(`[sku]     未找到属性区域`);
    return;
  }

  console.log(`[sku]     主色输入框: ${debug.totalInputs} 个`);

  const result = await page.evaluate(({ attrName, value }) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return null;
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        const container = wrap.querySelector('.sell-color-item-container');
        if (!container) return null;
        const inputs = container.querySelectorAll('input');
        for (const input of inputs) {
          // 只填"主色(必选)"，跳过"备注(可选)"
          if (input.placeholder === '主色(必选)' && (!input.value || input.value.trim() === '')) {
            input.value = value;
            input.dispatchEvent(new Event('input', { bubbles: true }));
            input.dispatchEvent(new Event('change', { bubbles: true }));
            return { success: true };
          }
        }
      }
    }
    return null;
  }, { attrName, value });

  if (result) {
    console.log(`[sku]     填写: ${value}`);
    // 点击属性区域外的空白处让输入框失焦，使 "+" 按钮可点击
    await page.evaluate(() => {
      const header = document.querySelector('.header') || document.querySelector('.header-bar') || document.body;
      header.scrollIntoView({ block: 'start' });
      header.click();
    });
    await page.waitForTimeout(500);
  } else {
    console.log(`[sku]     未找到空输入框`);
  }
}

async function clickAddButton(page, attrName) {
  // 先滚动到属性区域
  await page.evaluate((attrName) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return false;
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        wrap.scrollIntoView({ block: 'center' });
        return true;
      }
    }
    return false;
  }, attrName);
  await page.waitForTimeout(300);

  // 用 evaluate 获取按钮位置，然后用 Playwright 原生点击
  const coords = await page.evaluate((attrName) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return null;
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        const addBtn = wrap.querySelector('button.add');
        if (addBtn) {
          const rect = addBtn.getBoundingClientRect();
          return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
        }
      }
    }
    return null;
  }, attrName);

  if (!coords) {
    console.log(`[sku]     未找到"+"按钮`);
    return;
  }

  // Playwright 原生鼠标点击
  await page.mouse.click(coords.x, coords.y);
  await page.waitForTimeout(800);
}

async function selectDropdownOption(page, attrName, option) {
  await page.evaluate((attrName) => {
    const saleProps = document.querySelector('.sell-component-sale-props');
    if (!saleProps) return;
    const wraps = saleProps.querySelectorAll('.common-wrap');
    for (const wrap of wraps) {
      const label = wrap.querySelector('.props-label');
      if (label && label.textContent.trim().includes(attrName)) {
        const select = wrap.querySelector('.next-select-inner, .next-select');
        if (select) { select.click(); return; }
      }
    }
  }, attrName);
  await page.waitForTimeout(500);

  const selected = await page.evaluate((option) => {
    const overlay = document.querySelector('.next-overlay-wrapper, .options-content');
    if (!overlay) return false;
    const items = overlay.querySelectorAll('.options-item');
    for (const item of items) {
      const title = (item.getAttribute('title') || item.textContent || '').trim();
      if (title === option || title.includes(option) || option.includes(title)) {
        item.click();
        return true;
      }
    }
    return false;
  }, option);

  if (!selected) {
    await page.keyboard.press('Escape');
    console.log(`[sku]     下拉未找到: ${option}`);
  } else {
    console.log(`[sku]     选择: ${option}`);
  }
}


fillSku().catch(e => { console.error(`[sku] 异常: ${e.message}`); process.exit(1); });
