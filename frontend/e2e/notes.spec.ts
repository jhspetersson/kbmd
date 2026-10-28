import { expect, test } from '@playwright/test';
import { activeTitle, createNote, editor, noteExists, openNote, preview, readNote, saveState, typeInEditor, unique, waitSaved } from './helpers';

test('a typed note is autosaved and is still there after a reload', async ({ page, request }) => {
  const name = unique('Draft');
  await page.goto('/');
  await page.locator('.sidebar-actions button[title="New note"]').click();
  const input = page.locator('.modal .text-input');
  await expect(input).toHaveValue('Untitled');
  await input.fill(name);
  await page.locator('.modal button.primary').click();
  await expect(activeTitle(page)).toHaveText(name);

  await typeInEditor(page, 'Hello from the browser');
  await expect(saveState(page)).toHaveText('Unsaved changes');
  await waitSaved(page);
  expect(await readNote(request, `${name}.md`)).toContain('Hello from the browser');

  await page.reload();
  await expect(activeTitle(page)).toHaveText(name);
  await expect(editor(page)).toContainText('Hello from the browser');
});

test('wikilinks autocomplete, open the note on click, and create a missing one', async ({ page, request }) => {
  const target = unique('Target');
  const source = unique('Source');
  const fresh = unique('Brand new');
  await createNote(request, `${target}.md`, 'The target note.');
  await createNote(request, `${source}.md`, '');
  await openNote(page, `${source}.md`);

  // the first letters of the title are enough; Enter completes it and closes the brackets
  await typeInEditor(page, `[[${target.slice(0, 10)}`);
  const completion = page.locator('.cm-tooltip-autocomplete li', { hasText: target });
  await expect(completion).toBeVisible();
  await page.waitForTimeout(150); // CodeMirror ignores Enter for 75 ms after the list opens, against accidental picks
  await page.keyboard.press('Enter');
  await expect(editor(page)).toContainText(`[[${target}]]`);

  await page.keyboard.press('Enter');
  await page.keyboard.type(`[[${fresh}]]`);
  await page.keyboard.press('Escape'); // any completion still open
  await waitSaved(page);

  // the preview is rendered by the server: a link to an existing note opens it
  await preview(page).locator('a.wikilink', { hasText: target }).click();
  await expect(activeTitle(page)).toHaveText(target);
  await expect(editor(page)).toContainText('The target note.');

  // and a link to a note that does not exist yet creates it
  await page.locator('.tab', { hasText: source }).click();
  await preview(page).locator('a.wikilink.unresolved', { hasText: fresh }).click();
  await expect(activeTitle(page)).toHaveText(fresh);
  expect(await noteExists(request, `${fresh}.md`)).toBe(true);
});

test('a task ticked in the preview is ticked in the source', async ({ page, request }) => {
  const name = unique('Checklist');
  await createNote(request, `${name}.md`, '- [ ] First thing\n- [ ] Second thing\n');
  await openNote(page, `${name}.md`);

  await preview(page).locator('input[type=checkbox]').nth(1).click();
  await expect(editor(page)).toContainText('- [x] Second thing');
  await waitSaved(page);
  expect(await readNote(request, `${name}.md`)).toBe('- [ ] First thing\n- [x] Second thing\n');
});
