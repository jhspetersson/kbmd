import { expect, test } from '@playwright/test';
import { activeTitle, createNote, editor, openNote, readNote, unique } from './helpers';

test('renaming a note from the tree rewrites links and the open tab', async ({ page, request }) => {
  const alpha = unique('Alpha');
  const beta = unique('Beta');
  const gamma = unique('Gamma');
  await createNote(request, `${alpha}.md`, `See [[${beta}]] for more.`);
  await createNote(request, `${beta}.md`, 'The note being renamed.');
  await openNote(page, `${beta}.md`);

  await page.locator(`.tree-row[title="${beta}.md"]`).click({ button: 'right' });
  await page.locator('.context-menu button', { hasText: 'Rename' }).click();
  const input = page.locator('.modal .text-input');
  await expect(input).toHaveValue(`${beta}.md`);
  await input.fill(gamma); // the extension is kept when left out
  await page.locator('.modal button.primary').click();

  await expect(page.locator(`.tree-row[title="${gamma}.md"]`)).toBeVisible();
  await expect(page.locator(`.tree-row[title="${beta}.md"]`)).toHaveCount(0);
  await expect(activeTitle(page)).toHaveText(gamma);
  await expect(editor(page)).toContainText('The note being renamed.');
  expect(await readNote(request, `${alpha}.md`)).toBe(`See [[${gamma}]] for more.`);
});

test('deleting a note closes its tab and moves it to the trash', async ({ page, request }) => {
  const name = unique('Doomed');
  await createNote(request, `${name}.md`, 'Goodbye.');
  await openNote(page, `${name}.md`);

  page.once('dialog', (dialog) => void dialog.accept());
  await page.locator(`.tree-row[title="${name}.md"]`).click({ button: 'right' });
  await page.locator('.context-menu button', { hasText: 'Delete' }).click();

  await expect(page.locator(`.tree-row[title="${name}.md"]`)).toHaveCount(0);
  await expect(page.locator('.tab', { hasText: name })).toHaveCount(0);
  expect((await request.get(`/api/notes?path=${encodeURIComponent(`${name}.md`)}`)).status()).toBe(404);
});
