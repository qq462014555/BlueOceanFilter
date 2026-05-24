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

  // Record frames before
  const framesBefore = page.frames().map(f => f.url().substring(0, 80));
  console.log('Frames before:', framesBefore);

  // Click create spec
  await page.evaluate(() => {
    const xpath = "//*[contains(text(),'创建规格')]";
    const btn = document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;
    if (btn) { btn.click(); return true; }
    return false;
  });
  console.log('Clicked create spec');

  // Wait longer
  await new Promise(r => setTimeout(r, 10000));

  // Check frames after
  const framesAfter = page.frames().map(f => f.url().substring(0, 80));
  console.log('Frames after:', framesAfter);

  const newFrames = framesAfter.filter(f => !framesBefore.includes(f));
  console.log('New frames:', newFrames);

  // Check all frames for dialog content
  for (const frame of page.frames()) {
    try {
      const content = await frame.evaluate(() => {
        const dialog = document.querySelector('.next-drawer, .next-dialog, [role="dialog"], .dialog');
        if (!dialog) return null;
        const text = dialog.textContent.trim().substring(0, 500);
        const radios = dialog.querySelectorAll('.next-radio-wrapper');
        return {
          text,
          radiosCount: radios.length,
          html: dialog.innerHTML.substring(0, 500),
        };
      });
      if (content) {
        console.log('Dialog found in frame:', frame.url().substring(0, 60));
        console.log(JSON.stringify(content, null, 2));
      }
    } catch (e) {}
  }

  // Screenshot
  await page.screenshot({ path: 'G:/BlueOceanFilter/signal/debug_dialog2.png' });
  console.log('Screenshot saved');

  await pw.close();
})();
