// Prints html/*.html to pdf/*.pdf with Chromium (A4; S15 landscape).
const fs = require('fs'), path = require('path'), { pathToFileURL } = require('url');
const { chromium } = require(process.argv[2]);
(async () => {
  const dir = __dirname, out = path.join(dir, 'pdf');
  fs.mkdirSync(out, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage();
  for (const f of fs.readdirSync(path.join(dir, 'html'))) {
    const id = f.replace('.html', '');
    await p.goto(pathToFileURL(path.join(dir, 'html', f)).href);
    await p.pdf({ path: path.join(out, id + '.pdf'), format: 'A4', landscape: id === 'S15', printBackground: true });
  }
  await b.close();
  console.log('printed', fs.readdirSync(out).length);
})();
