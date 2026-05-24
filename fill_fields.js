const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const SIGNAL_DIR = path.join(__dirname, 'signal');
const MAX_RETRIES = 3;

async function fillFields() {
  // 读取信号目录，按时间排序取最早的一个
  const files = fs.readdirSync(SIGNAL_DIR)
    .filter(f => f.startsWith('fill_fields_') && f.endsWith('.json'))
    .map(f => path.join(SIGNAL_DIR, f))
    .sort((a, b) => fs.statSync(a).mtimeMs - fs.statSync(b).mtimeMs);

  if (files.length === 0) {
    console.log('[fill] 无新信号');
    return;
  }

  const signalFile = files[0];
  let signal;
  try {
    signal = JSON.parse(fs.readFileSync(signalFile, 'utf-8'));
  } catch (e) {
    console.log(`[fill] 信号文件JSON解析失败，删除: ${e.message}`);
    fs.unlinkSync(signalFile);
    return;
  }

  const jsonPath = signal.jsonPath;
  const productId = signal.productId;

  if (!jsonPath) {
    console.log('[fill] 信号文件缺少 jsonPath，删除');
    fs.unlinkSync(signalFile);
    return;
  }

  console.log(`[fill] 处理信号: ${path.basename(signalFile)} (${productId})`);

  let data;
  try {
    data = JSON.parse(fs.readFileSync(jsonPath, 'utf-8'));
  } catch (e) {
    console.log(`[fill] 字段JSON解析失败，删除信号文件: ${e.message}`);
    fs.unlinkSync(signalFile);
    return;
  }

  const fields = data.fields || [];
  if (fields.length === 0) {
    console.log('[fill] 没有需要填写的字段，删除信号文件');
    fs.unlinkSync(signalFile);
    return;
  }

  console.log(`[fill] 共 ${fields.length} 个字段`);
  console.log('[fill] 连接 Chrome CDP 9223...');
  const playwright = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const context = playwright.contexts()[0];
  let page = null;
  for (const p of context.pages()) {
    try { if ((await p.title()).includes('商品发布')) { page = p; break; } } catch (e) {}
  }
  if (!page) { console.log('[fill] 未找到商品发布页面'); process.exit(1); }

  await page.bringToFront();
  await page.evaluate('window.scrollTo(0, 0);');
  await page.waitForTimeout(500);

  // 需要填写的字段集合（按 label 标识）
  let pending = fields.filter(f => f.currentValue && f.currentValue.trim() !== '');
  let filled = new Map(); // label -> { success, matched }
  let retryCount = 0;

  while (pending.length > 0 && retryCount < MAX_RETRIES) {
    if (retryCount > 0) {
      console.log(`\n[fill] === 第 ${retryCount + 1} 轮重试，剩余 ${pending.length} 个字段 ===`);
      await page.waitForTimeout(1000);
    }

    let newFilled = 0;
    let stillPending = [];

    for (const f of pending) {
      const label = f.label;
      const type = f.type;
      const value = f.currentValue;

      console.log(`[fill] 填写 [${label}] (${type}) = ${value}`);

      try {
        const rowIdx = await page.evaluate((label) => {
          const rows = document.querySelectorAll('.default-items-item');
          for (let i = 0; i < rows.length; i++) {
            const labelEl = rows[i].querySelector('.sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label');
            if (!labelEl) continue;
            const title = labelEl.getAttribute('title') || labelEl.textContent.replace(/[*]/g, '').trim();
            if (title === label || title.includes(label)) return i;
          }
          return -1;
        }, label);

        if (rowIdx < 0) {
          console.log(`[fill]   未找到字段行`);
          filled.set(label, { success: false, reason: '字段不存在' });
          continue;
        }

        if (type === 'input' || type === 'measurement') {
          const input = page.locator('.default-items-item').nth(rowIdx).locator('input').first();
          await input.click();
          await page.waitForTimeout(200);
          await input.fill(value);
          await page.waitForTimeout(300);
          await input.press('Enter');
          await page.waitForTimeout(300);
          console.log(`[fill]   文本填写完成`);
          filled.set(label, { success: true, matched: value });
          newFilled++;
          continue;
        }

        // 点击打开下拉框
        await page.evaluate((idx) => {
          const rows = document.querySelectorAll('.default-items-item');
          const row = rows[idx];
          const ctrl = row.querySelector('.next-select, .next-select-inner, .next-input-control');
          if (ctrl) { ctrl.click(); }
        }, rowIdx);

        await page.waitForTimeout(2000);

        const isCheckbox = type === 'checkbox';

        const result = await page.evaluate(({ value, isCheckbox }) => {
          const selectOptions = document.querySelector('.sell-o-select-options');
          if (!selectOptions) return { found: false, reason: 'no sell-o-select-options' };

          const overlay = selectOptions.querySelector('.options-content');
          if (!overlay) return { found: false, reason: 'no options-content' };

          // 渐进式滚动加载所有选项
          let prevCount = 0;
          for (let i = 0; i < 50; i++) {
            overlay.scrollTop = overlay.scrollHeight;
            const currentCount = overlay.querySelectorAll('.options-item').length;
            if (currentCount === prevCount && i > 5) break;
            prevCount = currentCount;
          }

          const allItems = Array.from(overlay.querySelectorAll('.options-item'));

          // 精确匹配
          for (const item of allItems) {
            const title = (item.getAttribute('title') || item.textContent || '').trim();
            if (title === value) {
              if (isCheckbox) {
                const cb = item.querySelector('.next-checkbox, input[type="checkbox"], .info-content');
                if (cb) { cb.click(); } else { item.click(); }
              } else { item.click(); }
              return { found: true, matched: title, type: 'exact' };
            }
          }

          // 模糊匹配
          for (const item of allItems) {
            const title = (item.getAttribute('title') || item.textContent || '').trim();
            if (title.includes(value) || value.includes(title)) {
              if (isCheckbox) {
                const cb = item.querySelector('.next-checkbox, input[type="checkbox"], .info-content');
                if (cb) { cb.click(); } else { item.click(); }
              } else { item.click(); }
              return { found: true, matched: title, type: 'fuzzy' };
            }
          }

          // 搜索框输入
          const searchInput = selectOptions.querySelector('input[type="text"], .next-select-search-input, .next-input input, input');
          if (searchInput) {
            searchInput.value = value;
            searchInput.dispatchEvent(new Event('input', { bubbles: true }));
            searchInput.dispatchEvent(new Event('change', { bubbles: true }));
            return { found: false, method: 'search-input', totalItems: allItems.length };
          }

          return { found: false, reason: 'not found in ' + allItems.length + ' items' };
        }, { value, isCheckbox });

        await page.waitForTimeout(500);

        // 搜索型下拉
        if (!result.found && result.method === 'search-input') {
          await page.waitForTimeout(2000);
          const searchClick = await page.evaluate(({ value, isCheckbox }) => {
            const selectOptions = document.querySelector('.sell-o-select-options');
            if (!selectOptions) return { found: false, reason: 'selectOptions gone' };
            const overlay = selectOptions.querySelector('.options-content');
            if (!overlay) return { found: false, reason: 'overlay gone' };
            const items = overlay.querySelectorAll('.options-item');
            for (const item of items) {
              const title = (item.getAttribute('title') || item.textContent || '').trim();
              if (title.includes(value) || value.includes(title)) {
                if (isCheckbox) {
                  const cb = item.querySelector('.next-checkbox, input[type="checkbox"], .info-content');
                  if (cb) { cb.click(); } else { item.click(); }
                } else { item.click(); }
                return { found: true, matched: title };
              }
            }
            return { found: false, count: items.length, titles: Array.from(items).slice(0, 5).map(el => el.getAttribute('title')) };
          }, { value, isCheckbox });

          if (searchClick.found) {
            result.found = true;
            result.matched = searchClick.matched;
          } else if (label === '品牌') {
            // 品牌未找到，用 Playwright 原生键盘操作在搜索框输入"无品牌"
            const searchInput = page.locator('.sell-o-select-options .options-search input');
            if (await searchInput.count() > 0) {
              // 先全选再覆盖输入，确保清空旧值
              await searchInput.click();
              await page.keyboard.press('Control+a');
              await page.keyboard.type('无品牌');
              await page.waitForTimeout(2000);

              // 搜索结果更新后，查找"无品牌"选项并点击
              const noBrandClicked = await page.evaluate(() => {
                const items = document.querySelectorAll('.options-item');
                for (const item of items) {
                  const title = (item.getAttribute('title') || item.textContent || '').trim();
                  if (title === '无品牌' || title === '无品牌/无注册商标') {
                    item.click();
                    return true;
                  }
                }
                return false;
              });
              if (noBrandClicked) {
                console.log('[fill]   品牌未找到，已搜索并点击"无品牌"');
              } else {
                console.log('[fill]   品牌未找到，搜索"无品牌"后无匹配选项');
              }
            } else {
              console.log('[fill]   品牌未找到，且未找到搜索框');
            }
            result.found = true;
            result.matched = '无品牌';
          } else {
            console.log(`[fill]   搜索后: ${JSON.stringify(searchClick)}`);
          }
        }

        console.log(`[fill]   ${JSON.stringify(result)}`);
        if (result.found) {
          filled.set(label, { success: true, matched: result.matched });
          newFilled++;
        } else {
          stillPending.push(f);
        }

      } catch (e) {
        console.log(`[fill]   异常: ${e.message}`);
        stillPending.push(f);
      }

      await page.waitForTimeout(400);
    }

    pending = stillPending;
    retryCount++;
  }

  // === 自动校验：读取页面实际值 ===
  console.log('\n[verify] 开始校验页面实际值...');
  const verifyResult = await page.evaluate(() => {
    const rows = document.querySelectorAll('.default-items-item');
    const out = [];
    for (const row of rows) {
      const labelEl = row.querySelector('.sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label');
      if (!labelEl) continue;
      let label = labelEl.getAttribute('title') || labelEl.textContent.replace(/[*]/g, '').trim();
      const input = row.querySelector('input');
      const value = input ? input.value : '';
      const em = row.querySelector('em');
      const display = em ? (em.getAttribute('title') || em.textContent.trim()) : '';
      const tags = Array.from(row.querySelectorAll('.next-tag, .next-select-tag, .tag, .next-tag-body')).map(t => t.textContent.trim());
      const valueEls = Array.from(row.querySelectorAll('.next-select-value, .next-select-display, .sell-o-info, .info-content'));
      const valueTexts = valueEls.map(el => el.textContent.trim()).filter(Boolean);
      out.push({ label, input: value || '', display: display || '', tags, valueTexts });
    }
    return out;
  });

  let verifyOk = 0, verifyFail = 0;
  for (const [label, info] of filled.entries()) {
    const actual = verifyResult.find(r => r.label.includes(label));
    let actualVal = '(空)';
    if (actual) {
      actualVal = actual.input || actual.display || '';
      if (!actualVal && actual.tags && actual.tags.length > 0) actualVal = actual.tags.join(', ');
      if (!actualVal && actual.valueTexts && actual.valueTexts.length > 0) actualVal = actual.valueTexts.join(', ');
      if (!actualVal) actualVal = '(空)';
    }

    if (info.success) {
      const isBrandOk = label.includes('品牌') && (actualVal.includes('无品牌') || actualVal.includes(info.matched));
      if (actualVal !== '(空)' || isBrandOk) {
        verifyOk++;
        console.log(`[verify] ✅ [${label}] → ${actualVal}`);
      } else {
        verifyFail++;
        console.log(`[verify] ❌ [${label}] 预期 '${info.matched}' 实际: ${actualVal}`);
      }
    } else {
      verifyFail++;
      console.log(`[verify] ❌ [${label}] 填写失败: ${info.reason || '未知'}`);
    }
  }

  console.log(`\n[verify] 校验完成: 通过 ${verifyOk}, 未通过 ${verifyFail}`);
  console.log(`[fill] 总计: 填写 ${filled.size} 个, 校验通过 ${verifyOk}, 剩余 ${pending.length} 个未填`);

  // 截图
  const screenshotName = `fill_${Date.now()}.png`;
  const screenshotPath = path.join(SIGNAL_DIR, screenshotName);
  const screenshot = await page.screenshot({ fullPage: true });
  fs.writeFileSync(screenshotPath, screenshot);
  console.log(`[fill] 截图: ${screenshotPath}`);

  await playwright.close();

  // 无论成功失败，都删除信号文件
  fs.unlinkSync(signalFile);
  console.log(`[fill] 已删除信号文件: ${path.basename(signalFile)}`);
}

fillFields().catch(e => { console.error(`[fill] 异常: ${e.message}`); process.exit(1); });
