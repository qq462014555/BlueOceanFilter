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
  await new Promise(r => setTimeout(r, 3000));

  // Search ALL frames for inputs with '主色' placeholder
  const frames = page.frames();
  let found = false;
  for (const frame of frames) {
    try {
      const inputs = await frame.evaluate(() => {
        return Array.from(document.querySelectorAll('input[placeholder*="主色"]')).map(inp => ({
          placeholder: inp.placeholder,
          value: inp.value,
          className: inp.className,
          parent: inp.parentElement ? inp.parentElement.className.substring(0, 60) : '',
        }));
      });
      if (inputs.length > 0) {
        found = true;
        console.log('Frame URL:', frame.url().substring(0, 80));
        inputs.forEach(i => console.log('Input:', JSON.stringify(i)));
      }
    } catch (e) {}
  }
  if (!found) console.log('No inputs with 主色 found');

  // Search all inputs in all frames
  for (const frame of frames) {
    try {
      const info = await frame.evaluate(() => {
        const inputs = Array.from(document.querySelectorAll('input'));
        return inputs.map(inp => ({
          placeholder: inp.placeholder,
          type: inp.type,
          value: inp.value.substring(0, 20),
          className: inp.className.substring(0, 40),
        })).slice(0, 5);
      });
      if (info.length > 0) {
        console.log('Frame inputs:', frame.url().substring(0, 60), info.length);
        console.log('Sample:', JSON.stringify(info));
      }
    } catch (e) {}
  }

  await pw.close();
})();
