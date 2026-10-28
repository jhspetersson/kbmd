import { expect, test } from '@playwright/test';
import { activeTab, createNote, editor, ribbon, unique } from './helpers';

// a phone: the side panels cover the note, and the Android app's back gesture closes whatever is on top
test.use({ viewport: { width: 390, height: 800 }, hasTouch: true, isMobile: true });

test('on a phone the panels are overlays and the back gesture closes them', async ({ page, request }) => {
  const name = unique('Pocket');
  await createNote(request, `${name}.md`, 'Read on the go.');
  await page.goto('/');
  await expect(activeTab(page)).toHaveAttribute('title', 'Welcome.md'); // the first visit opens it, closing any panel

  const files = page.locator('.sidebar.left');
  await expect(files).toHaveCount(0);
  await ribbon(page, 'Files').click();
  await expect(files).toBeVisible();
  await expect(page.locator('.sidebar-backdrop')).toBeVisible();

  // picking a note closes the panel; phones start in editor-only mode
  await page.locator(`.tree-row[title="${name}.md"]`).click();
  await expect(files).toHaveCount(0);
  await expect(activeTab(page)).toHaveAttribute('title', `${name}.md`);
  await expect(editor(page)).toContainText('Read on the go.');
  await expect(page.locator('.preview')).toHaveCount(0);

  const back = () => page.evaluate(() => (window as unknown as { __kbmd: { back: () => boolean } }).__kbmd.back());
  expect(await back()).toBe(false); // nothing to close: the app itself may go to the background
  await ribbon(page, 'Search (Ctrl+Shift+F)').click();
  await expect(page.locator('.panel-input')).toBeVisible();
  expect(await back()).toBe(true);
  await expect(page.locator('.sidebar.left')).toHaveCount(0);
});
