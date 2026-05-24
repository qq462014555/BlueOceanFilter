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

  // Wait for dialog
  await new Promise(r => setTimeout(r, 10000));

  // Find dialog frame
  let dialogFrame = null;
  for (const frame of page.frames()) {
    try {
      const text = await frame.evaluate(() => {
        const dialog = document.querySelector('.next-drawer, .next-dialog, [role="dialog"]');
        return dialog ? dialog.textContent.trim() : '';
      });
      if (text.includes('选择标准属性')) {
        dialogFrame = frame;
        console.log('Dialog frame:', frame.url().substring(0, 80));
        break;
      }
    } catch (e) {}
  }

  if (!dialogFrame) { console.log('No dialog frame'); await pw.close(); process.exit(1); }

  // Use Playwright locators on the frame
  console.log('Using Playwright locators...');

  // Find and click the radio button
  const radioLocator = dialogFrame.locator('.next-radio-label:has-text("选择标准属性构建规格")');
  await radioLocator.scrollIntoViewIfNeeded();
  await radioLocator.click();
  console.log('Clicked radio');
  await new Promise(r => setTimeout(r, 2000));

  // Find and click the checkbox for 颜色分类
  const checkboxLocator = dialogFrame.locator('.multi-sale-props .prop-item:has-text("颜色分类")');
  await checkboxLocator.scrollIntoViewIfNeeded();
  await checkboxLocator.click();
  console.log('Clicked checkbox');
  await new Promise(r => setTimeout(r, 1000));

  // Find and click 确定 button
  const confirmLocator = dialogFrame.locator('button:has-text("确定")');
  await confirmLocator.scrollIntoViewIfNeeded();
  await confirmLocator.click();
  console.log('Clicked 确定');
  await new Promise(r => setTimeout(r, 5000));

  // Check page
  const check = await page.evaluate(() => {
    const wrapper = document.querySelector('.sell-sku-table-wrapper-new');
    if (!wrapper) return { found: false };
    return {
      found: true,
      text: wrapper.textContent.trim().substring(0, 200),
    };
  });
  console.log('Page check:', JSON.stringify(check));

  // Check all frames for inputs
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
  await page.screenshot({ path: 'G:/BlueOceanFilter/signal/debug_final.png', fullPage: true });
  console.log('Screenshot saved');

  await pw.close();
})();
