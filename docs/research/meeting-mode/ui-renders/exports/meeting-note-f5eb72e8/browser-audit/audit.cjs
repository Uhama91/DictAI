'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { chromium } = require('playwright');

async function main() {
const root = '/Users/ulliemaillot/.codex/worktrees/dictai-meeting-mode/phone-whisper';
const source = path.join(
  root,
  'app/build/reports/meeting/t8-export/android-run/artifacts/meeting-note-f5eb72e8-1eb5-41a8-a3fb-a43a916ed5e7/meeting-note.html',
);
const outDir = path.join(root, 'app/build/reports/meeting/t8-export/browser-audit');
const dpr = 2;
const html = await fs.readFile(source);
const sourceHash = crypto.createHash('sha256').update(html).digest('hex');
const externalUrlsInSource = [...new Set(html.toString('utf8').match(/https?:\/\/[^\s"'<>]+/g) || [])];
const externalRequests = [];
const observedSchemes = {};
const consoleMessages = [];
const pageErrors = [];

function pngDimensions(buffer) {
  assert.equal(buffer.toString('ascii', 1, 4), 'PNG');
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

async function openIsolatedPage(width, height, mobile) {
  const context = await browser.newContext({
    viewport: { width, height },
    deviceScaleFactor: dpr,
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: 'light',
    serviceWorkers: 'block',
  });
  await context.setOffline(true);
  const abortExternal = async route => {
    const url = route.request().url();
    externalRequests.push(url);
    await route.abort('blockedbyclient');
  };
  await context.route('http://**/*', abortExternal);
  await context.route('https://**/*', abortExternal);

  const page = await context.newPage();
  page.on('request', request => {
    let scheme = 'unknown:';
    try { scheme = new URL(request.url()).protocol; } catch (_) {}
    observedSchemes[scheme] = (observedSchemes[scheme] || 0) + 1;
  });
  page.on('console', message => consoleMessages.push({ type: message.type(), text: message.text() }));
  page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(pathToFileURL(source).href, { waitUntil: 'load' });
  const images = await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(Array.from(document.images, image => image.decode()));
    return Array.from(document.images, image => ({
      alt: image.alt,
      complete: image.complete,
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
    }));
  });
  assert.equal(images.length, 2, `expected two embedded images at ${width}px`);
  assert(images.every(image => image.complete && image.naturalWidth > 0 && image.naturalHeight > 0),
    `all embedded images must decode at ${width}px`);
  return { context, page, images };
}

async function measureViewport(page) {
  return page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    navigatorOnline: navigator.onLine,
    documentWidth: document.documentElement.scrollWidth,
    bodyWidth: document.body.scrollWidth,
    imageStates: Array.from(document.images, image => ({
      complete: image.complete,
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
      renderedWidth: Math.round(image.getBoundingClientRect().width),
    })),
    toggles: Array.from(document.querySelectorAll('details.image-size'), detail => detail.open),
  }));
}

async function capture(page, filePath) {
  const png = await page.screenshot({ path: filePath, fullPage: true, animations: 'disabled' });
  return pngDimensions(png);
}

const browser = await chromium.launch({
  headless: true,
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  args: [
    '--disable-background-networking',
    '--disable-sync',
    '--disable-default-apps',
    '--no-first-run',
    '--no-default-browser-check',
  ],
});

try {
  const browserVersion = browser.version();
  const mobile = await openIsolatedPage(390, 844, true);
  const mobileInitial = await measureViewport(mobile.page);
  assert.equal(mobileInitial.navigatorOnline, false, 'mobile browser context must remain offline');
  assert(mobileInitial.documentWidth <= mobileInitial.viewportWidth, 'mobile document has horizontal overflow');
  assert(mobileInitial.bodyWidth <= mobileInitial.viewportWidth, 'mobile body has horizontal overflow');
  assert(mobileInitial.toggles.every(open => !open), 'image-size toggles should start collapsed');
  const mobilePath = path.join(outDir, 'meeting-note-390-fullpage.png');
  const mobileScreenshot = await capture(mobile.page, mobilePath);
  assert.equal(mobileScreenshot.width, 390 * dpr);

  const toggle = mobile.page.locator('#image-1 details.image-size > summary');
  assert.equal(await toggle.count(), 1, 'image 1 must expose one native details toggle');
  await toggle.click();
  await mobile.page.waitForFunction(() => document.querySelector('#image-1 details.image-size')?.open === true);
  const mobileExpanded = await measureViewport(mobile.page);
  assert(mobileExpanded.documentWidth <= mobileExpanded.viewportWidth, 'expanded mobile document has horizontal overflow');
  assert(mobileExpanded.bodyWidth <= mobileExpanded.viewportWidth, 'expanded mobile body has horizontal overflow');
  assert(mobileExpanded.toggles[0] === true, 'real click must expand image 1');
  const expandedPath = path.join(outDir, 'meeting-note-390-image-1-expanded.png');
  const expandedScreenshot = await capture(mobile.page, expandedPath);
  assert.equal(expandedScreenshot.width, 390 * dpr);
  await toggle.click();
  await mobile.page.waitForFunction(() => document.querySelector('#image-1 details.image-size')?.open === false);
  const mobileRestored = await measureViewport(mobile.page);
  assert(mobileRestored.toggles.every(open => !open), 'real second click must restore collapsed state');
  await mobile.context.close();

  const desktop = await openIsolatedPage(1280, 900, false);
  const desktopMetrics = await measureViewport(desktop.page);
  assert.equal(desktopMetrics.navigatorOnline, false, 'desktop browser context must remain offline');
  assert(desktopMetrics.documentWidth <= desktopMetrics.viewportWidth, 'desktop document has horizontal overflow');
  assert(desktopMetrics.bodyWidth <= desktopMetrics.viewportWidth, 'desktop body has horizontal overflow');
  const desktopPath = path.join(outDir, 'meeting-note-1280-fullpage.png');
  const desktopScreenshot = await capture(desktop.page, desktopPath);
  assert.equal(desktopScreenshot.width, 1280 * dpr);
  const desktopToggle = desktop.page.locator('#image-1 details.image-size > summary');
  await desktopToggle.click();
  await desktop.page.waitForFunction(() => document.querySelector('#image-1 details.image-size')?.open === true);
  const desktopExpanded = await measureViewport(desktop.page);
  assert(desktopExpanded.imageStates[0].renderedWidth > desktopMetrics.imageStates[0].renderedWidth,
    'desktop click should enlarge image 1 when wider space is available');
  await desktopToggle.click();
  await desktop.page.waitForFunction(() => document.querySelector('#image-1 details.image-size')?.open === false);
  const desktopRestored = await measureViewport(desktop.page);
  assert(desktopRestored.toggles.every(open => !open), 'desktop second click must restore collapsed state');
  await desktop.context.close();

  assert.deepEqual(externalUrlsInSource, [], 'source HTML must not reference external HTTP(S) resources');
  assert.deepEqual(externalRequests, [], 'browser must not issue external HTTP(S) requests');
  assert.deepEqual(pageErrors, [], 'browser page should have no uncaught errors');
  const consoleErrors = consoleMessages.filter(message => message.type === 'error');
  assert.deepEqual(consoleErrors, [], 'browser console should have no errors');

  const report = {
    status: 'pass',
    headless: true,
    browser: 'Chromium via installed Playwright',
    chromiumVersion: browserVersion,
    isolatedFreshContexts: true,
    networkOffline: true,
    httpHttpsRequestsBlocked: true,
    deviceScaleFactor: dpr,
    source: path.basename(source),
    sourceSha256: sourceHash,
    sourceExternalHttpReferences: externalUrlsInSource.length,
    externalHttpRequests: externalRequests,
    observedResourceSchemeCounts: observedSchemes,
    consoleMessages,
    uncaughtPageErrors: pageErrors,
    viewports: {
      mobile390: {
        initial: mobileInitial,
        screenshot: path.basename(mobilePath),
        screenshotPixels: mobileScreenshot,
        expandedImage1: mobileExpanded,
        expandedScreenshot: path.basename(expandedPath),
        expandedScreenshotPixels: expandedScreenshot,
        restoredToggleState: mobileRestored.toggles,
      },
      desktop1280: {
        metrics: desktopMetrics,
        screenshot: path.basename(desktopPath),
        screenshotPixels: desktopScreenshot,
        expandedImage1: desktopExpanded,
        restoredToggleState: desktopRestored.toggles,
      },
    },
  };
  await fs.writeFile(path.join(outDir, 'browser-audit.json'), `${JSON.stringify(report, null, 2)}\n`);
  await fs.writeFile(path.join(outDir, 'browser-audit-status.txt'), 'PASS: 390px/1280px full-page render, images decoded, image 1 toggle opened and restored, no horizontal overflow, no external HTTP(S), no console/page errors.\n');
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`);
} finally {
  await browser.close();
}
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
