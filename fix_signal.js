const fs = require('fs');
const path = require('path');
const SIGNAL_DIR = path.join(__dirname, 'signal');
const files = fs.readdirSync(SIGNAL_DIR).filter(f => f.startsWith('fill_fields_') && f.endsWith('.json'));
files.forEach(f => {
  const fp = path.join(SIGNAL_DIR, f);
  let content = fs.readFileSync(fp, 'utf-8');
  // Replace unescaped backslashes with escaped ones
  content = content.replace(/(?<!\\)\\(?!["\\/bfnrtu])/g, '\\\\');
  fs.writeFileSync(fp, content, 'utf-8');
  console.log('Fixed: ' + f);
  // Verify it's valid JSON now
  try { JSON.parse(content); console.log('  Valid JSON'); } catch(e) { console.log('  Still invalid: ' + e.message); }
});
