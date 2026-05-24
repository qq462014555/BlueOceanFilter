const { chromium } = require('playwright-core');
(async () => {
  const pw = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const context = pw.contexts()[0];
  let page = null;
  for (const p of context.pages()) {
    try { if ((await p.title()).includes('商品发布')) { page = p; break; } } catch (e) {}
  }
  if (!page) { console.log('not found'); process.exit(1); }
  await page.bringToFront();

  // Step 1: Click create spec
  console.log('Step 1: Click create spec...');
  const clickResult = await page.evaluate(() => {
    const xpath = "//*[contains(text(),'创建规格')]";
    const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
    if (btn) { btn.click(); return { ok: true }; }
    return { ok: false };
  });
  console.log(JSON.stringify(clickResult));
  await new Promise(r => setTimeout(r, 3000));

  // Check dialog
  console.log('Step 2: Check dialog...');
  const dialogInfo = await page.evaluate(() => {
    const dialog = document.querySelector('.next-dialog, [role="dialog"]');
    if (!dialog) return { exists: false };
    const text = dialog.textContent.trim().substring(0, 300);
    const radios = dialog.querySelectorAll('.next-radio-wrapper');
    return { exists: true, text, radiosCount: radios.length };
  });
  console.log(JSON.stringify(dialogInfo, null, 2));

  if (!dialogInfo.exists) { console.log('No dialog, trying again...'); process.exit(1); }

  // Step 3: Select radio
  console.log('Step 3: Select radio...');
  const radioResult = await page.evaluate(() => {
    const dialog = document.querySelector('.next-dialog, [role="dialog"]');
    if (!dialog) return { ok: false };
    const radios = dialog.querySelectorAll('.next-radio-wrapper');
    for (const radio of radios) {
      const label = radio.querySelector('.next-radio-label');
      if (label && label.textContent.trim().includes('选择标准属性构建规格')) {
        const input = radio.querySelector('input[type="radio"]');
        if (input) { input.click(); return { ok: true, method: 'input' }; }
        radio.click(); return { ok: true, method: 'radio' };
      }
    }
    return { ok: false, radiosCount: radios.length };
  });
  console.log(JSON.stringify(radioResult));
  await new Promise(r => setTimeout(r, 2000));

  // Step 4: Check if prop items appeared
  console.log('Step 4: Check prop items...');
  const propInfo = await page.evaluate(() => {
    const dialog = document.querySelector('.next-dialog, [role="dialog"]');
    if (!dialog) return { dialog: false };
    const propItems = dialog.querySelectorAll('.multi-sale-props .prop-item');
    return { dialog: true, propItemsCount: propItems.length };
  });
  console.log(JSON.stringify(propInfo));

  if (propInfo.propItemsCount === 0) { console.log('No prop items, exiting'); process.exit(1); }

  // Step 5: Click 颜色分类
  console.log('Step 5: Click 颜色分类...');
  const colorResult = await page.evaluate(() => {
    const dialog = document.querySelector('.next-dialog, [role="dialog"]');
    if (!dialog) return { ok: false };
    const propItems = dialog.querySelectorAll('.multi-sale-props .prop-item');
    for (const item of propItems) {
      const span = item.querySelector('span');
      if (span && span.textContent.trim() === '颜色分类') {
        const cb = item.querySelector('.next-checkbox input, input[type="checkbox"]');
        if (cb) { cb.click(); return { ok: true, method: 'checkbox' }; }
        item.click(); return { ok: true, method: 'item' };
      }
    }
    return { ok: false };
  });
  console.log(JSON.stringify(colorResult));
  await new Promise(r => setTimeout(r, 1000));

  // Step 6: Click 确定
  console.log('Step 6: Click 确定...');
  const confirmResult = await page.evaluate(() => {
    const dialog = document.querySelector('.next-dialog, [role="dialog"]');
    if (!dialog) return { ok: false };
    const btns = dialog.querySelectorAll('button, [role="button"]');
    for (const btn of btns) {
      const text = btn.textContent.trim();
      if (text.includes('确定')) {
        btn.click(); return { ok: true, text };
      }
    }
    return { ok: false, btnsCount: btns.length };
  });
  console.log(JSON.stringify(confirmResult));
  await new Promise(r => setTimeout(r, 3000));

  // Step 7: Check result
  console.log('Step 7: Check result...');
  const check = await page.evaluate(() => {
    const result = { wrappers: [] };
    document.querySelectorAll('div').forEach(el => {
      const cls = el.className || '';
      const txt = (el.textContent || '').trim();
      if (txt.includes('主色') || txt.includes('颜色分类') || txt.includes('备注')) {
        if (txt.length > 2 && txt.length < 200) {
          result.wrappers.push({ cls: cls.substring(0, 60), text: txt.substring(0, 80) });
        }
      }
    });
    return result;
  });
  console.log(JSON.stringify(check, null, 2));

  // Screenshot
  await page.screenshot({ path: 'G:/BlueOceanFilter/signal/debug_after_spec.png', fullPage: true });
  console.log('Screenshot saved');

  await pw.close();
})();
