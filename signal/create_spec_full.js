const { chromium } = require('playwright-core');
(async () => {
  const pw = await chromium.connectOverCDP('http://127.0.0.1:9223');
  const context = pw.contexts()[0];
  let page = null;
  for (const p of context.pages()) {
    try { if ((await p.title()).includes('商品发布')) { page = p; break; } } catch (e) {}
  }
  await page.bringToFront();
  await page.evaluate('window.scrollTo(0, document.body.scrollHeight);');
  await new Promise(r => setTimeout(r, 2000));

  // Click create spec
  await page.evaluate(() => {
    const xpath = "//*[contains(text(),'创建规格')]";
    const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
    if (btn) { btn.click(); return true; }
    return false;
  });
  console.log('Clicked create spec');

  // Wait for dialog content to load
  await new Promise(r => setTimeout(r, 10000));

  // Find the frame with dialog content
  let dialogFrame = null;
  for (const frame of page.frames()) {
    try {
      const text = await frame.evaluate(() => {
        const dialog = document.querySelector('.next-drawer, .next-dialog, [role="dialog"]');
        return dialog ? dialog.textContent.trim() : '';
      });
      if (text.includes('选择标准属性')) {
        dialogFrame = frame;
        console.log('Found dialog in frame:', frame.url().substring(0, 80));
        break;
      }
    } catch (e) {}
  }

  if (!dialogFrame) {
    console.log('Dialog frame not found');
    await pw.close();
    process.exit(1);
  }

  // Step 1: Select "选择标准属性构建规格"
  console.log('Selecting radio...');
  await dialogFrame.evaluate(() => {
    const radios = document.querySelectorAll('.next-radio-wrapper');
    for (const radio of radios) {
      const label = radio.querySelector('.next-radio-label');
      if (label && label.textContent.trim().includes('选择标准属性构建规格')) {
        radio.click();
        return true;
      }
    }
    return false;
  });
  await new Promise(r => setTimeout(r, 2000));

  // Step 2: Check "颜色分类"
  console.log('Checking 颜色分类...');
  await dialogFrame.evaluate(() => {
    const propItems = document.querySelectorAll('.multi-sale-props .prop-item');
    for (const item of propItems) {
      const span = item.querySelector('span');
      if (span && span.textContent.trim() === '颜色分类') {
        item.click();
        return true;
      }
    }
    return false;
  });
  await new Promise(r => setTimeout(r, 1000));

  // Step 3: Click 确定
  console.log('Clicking 确定...');
  await dialogFrame.evaluate(() => {
    const dialog = document.querySelector('.next-drawer, .next-dialog, [role="dialog"]');
    if (!dialog) return false;
    const btns = dialog.querySelectorAll('button, [role="button"]');
    for (const btn of btns) {
      const text = btn.textContent.trim();
      if (text.includes('确定')) {
        btn.click();
        return true;
      }
    }
    return false;
  });
  await new Promise(r => setTimeout(r, 5000));

  // Step 4: Check page for SKU inputs
  console.log('Checking page for SKU inputs...');
  const pageCheck = await page.evaluate(() => {
    const result = { inputs: [] };
    document.querySelectorAll('input[placeholder*="主色"]').forEach(inp => {
      result.inputs.push({
        placeholder: inp.placeholder,
        value: inp.value,
        parent: inp.parentElement ? inp.parentElement.className.substring(0, 60) : '',
      });
    });
    return result;
  });
  console.log('Page check:', JSON.stringify(pageCheck, null, 2));

  // Also check all frames
  for (const frame of page.frames()) {
    try {
      const inputs = await frame.evaluate(() => {
        return Array.from(document.querySelectorAll('input[placeholder*="主色"]')).map(inp => ({
          placeholder: inp.placeholder,
          value: inp.value,
        }));
      });
      if (inputs.length > 0) {
        console.log('Found inputs in frame:', frame.url().substring(0, 60));
        console.log(JSON.stringify(inputs));
      }
    } catch (e) {}
  }

  // Screenshot
  await page.screenshot({ path: 'G:/BlueOceanFilter/signal/debug_after_create.png', fullPage: true });
  console.log('Screenshot saved');

  await pw.close();
})();
