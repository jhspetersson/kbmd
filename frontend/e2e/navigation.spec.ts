import { expect, test } from '@playwright/test';
import { activeTab, activeTitle, createNote, noteExists, ribbon, unique } from './helpers';

test('the quick switcher opens and creates notes, and the URL brings a note back after a reload', async ({ page, request }) => {
  const existing = unique('Switch me');
  const fresh = unique('Fresh');
  await createNote(request, `${existing}.md`, 'Found through the switcher.');
  await page.goto('/');

  await page.keyboard.press('Control+o');
  await page.locator('.switcher-input').fill(existing.toLowerCase());
  await expect(page.locator('.switcher-row.selected')).toContainText(existing);
  await page.keyboard.press('Enter');
  await expect(activeTitle(page)).toHaveText(existing);
  await expect(page).toHaveURL(new RegExp(`#/${encodeURI(existing)}\\.md$`));

  await page.reload();
  await expect(activeTitle(page)).toHaveText(existing);

  await page.keyboard.press('Control+o');
  await page.locator('.switcher-input').fill(fresh);
  await page.locator('.switcher-row', { hasText: `Create “${fresh}”` }).click();
  await expect(activeTitle(page)).toHaveText(fresh);
  expect(await noteExists(request, `${fresh}.md`)).toBe(true);
  // both notes stay as tabs (next to the welcome note a first visit opens)
  await expect(page.locator(`.tab[title="${existing}.md"]`)).toHaveCount(1);
  await expect(page.locator(`.tab[title="${fresh}.md"]`)).toHaveCount(1);
});

test('full-text search finds a phrase and opens the note', async ({ page, request }) => {
  const name = unique('Needle');
  const word = `zxqv${Math.random().toString(36).slice(2, 7)}`;
  await createNote(request, `${name}.md`, `A line with the rare word ${word} in it.`);
  await page.goto('/');

  await page.keyboard.press('Control+Shift+f');
  await page.locator('.panel-input').fill(word.slice(0, 6)); // prefixes match while typing
  const hit = page.locator('.hit', { hasText: name });
  await expect(hit).toBeVisible();
  await expect(hit.locator('mark').first()).toBeVisible();
  await hit.click();
  await expect(activeTitle(page)).toHaveText(name);
});

test('the tags panel lists a tag and turns it into a search', async ({ page, request }) => {
  const name = unique('Tagged');
  const tag = `e2e${Math.random().toString(36).slice(2, 7)}`;
  await createNote(request, `${name}.md`, `Some text #${tag} here.`);
  await page.goto('/');

  await ribbon(page, 'Tags').click();
  await page.locator('.tag-row', { hasText: `#${tag}` }).click();
  await expect(page.locator('.panel-input')).toHaveValue(`tag:${tag}`);
  await page.locator('.hit', { hasText: name }).click();
  await expect(activeTab(page)).toHaveAttribute('title', `${name}.md`);
});
