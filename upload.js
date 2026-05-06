const { chromium } = require('playwright');
const path = require('path');
const fs = require('fs');

(async () => {
    const uploadFilePath = process.argv[2];

    if (!uploadFilePath) {
        console.error('Usage: node upload.js <Excel file path>');
        process.exit(1);
    }

    const absPath = path.resolve(uploadFilePath);
    if (!fs.existsSync(absPath)) {
        console.error('File not found: ' + absPath);
        process.exit(1);
    }

    console.log('Launching browser...');
    const browser = await chromium.launch({ headless: false });
    const page = await browser.newPage();

    console.log('Opening page...');
    await page.goto('http://localhost:8080/index.html');
    await page.waitForLoadState('networkidle');

    const fileInput = await page.$('input[type="file"]');
    if (!fileInput) {
        console.error('File input not found on page');
        await browser.close();
        process.exit(1);
    }

    console.log('Uploading: ' + absPath);
    await fileInput.setInputFiles(absPath);
    await page.waitForTimeout(1000);

    const buttons = [
        'button:has-text("开始筛选")',
        'button:has-text("上传")',
        'button:has-text("提交")',
        'button:has-text("确认")',
        'button:has-text("筛选")',
        'button[type="submit"]',
    ];

    for (const sel of buttons) {
        const btn = await page.$(sel);
        if (btn) {
            console.log('Clicking: ' + sel);
            await btn.click();
            break;
        }
    }

    console.log('Done! Browser will stay open so you can check progress.');
})();
