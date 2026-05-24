const { chromium } = require('playwright-core');

(async () => {
  const pw = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const context = pw.contexts()[0];
  let page = null;
  for (const p of context.pages()) {
    try { if ((await p.title()).includes('商品发布')) { page = p; break; } } catch (e) {}
  }
  if (!page) { console.log('未找到商品发布页面'); process.exit(1); }
  await page.bringToFront();

  // 找到"品牌"字段行
  const rowIdx = await page.evaluate(() => {
    const rows = document.querySelectorAll('.default-items-item');
    for (let i = 0; i < rows.length; i++) {
      const labelEl = rows[i].querySelector('.sell-component-info-wrapper-label [title], .sell-component-info-wrapper-label');
      if (!labelEl) continue;
      const title = labelEl.getAttribute('title') || labelEl.textContent.replace(/[*]/g, '').trim();
      if (title.includes('品牌')) return i;
    }
    return -1;
  });

  // 点击打开下拉框
  await page.evaluate((idx) => {
    const rows = document.querySelectorAll('.default-items-item');
    const row = rows[idx];
    const ctrl = row.querySelector('.next-select, .next-select-inner, .next-input-control');
    if (ctrl) { ctrl.click(); }
  }, rowIdx);

  await page.waitForTimeout(2000);

  // 先清空搜索框，然后输入"无品牌"
  await page.evaluate(() => {
    const input = document.querySelector('.sell-o-select-options .options-search input');
    console.log('找到搜索框:', !!input);
    if (input) {
      input.value = '';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.value = '无品牌';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
      // 触发 React 的 onChange
      const tracker = input._valueTracker;
      if (tracker) tracker.setValue('');
      const event = new Event('input', { bubbles: true });
      event.simulated = true;
      input.dispatchEvent(event);
    }
  });

  await page.waitForTimeout(3000);

  // 搜索后打印所有选项
  const afterSearch = await page.evaluate(() => {
    const items = document.querySelectorAll('.options-item');
    return Array.from(items).map(el => ({
      title: el.getAttribute('title'),
      text: el.textContent.trim()
    }));
  });

  console.log('搜索"无品牌"后的选项:');
  if (afterSearch.length === 0) {
    console.log('  (无选项)');
    // 看看有没有"无品牌"相关的其他元素
    const allText = await page.evaluate(() => {
      const container = document.querySelector('.sell-o-select-options');
      return container ? container.innerText : '无';
    });
    console.log('下拉框全部文本:');
    console.log(allText.substring(0, 500));
  } else {
    afterSearch.forEach((o, i) => console.log(`  ${i+1}. title="${o.title}" text="${o.text}"`));
  }

  await pw.close();
})();
