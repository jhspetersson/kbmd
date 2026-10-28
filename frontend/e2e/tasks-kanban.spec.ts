import { expect, test } from '@playwright/test';
import { createNote, editor, openNote, readNote, ribbon, unique } from './helpers';

test('ticking a task in the Tasks tab edits the note, and the open editor picks it up', async ({ page, request }) => {
  const name = unique('Todo');
  await createNote(request, `${name}.md`, `- [ ] Buy milk ${name}\n- [ ] Call home ${name}\n`);
  await openNote(page, `${name}.md`);
  await expect(editor(page)).toContainText('- [ ] Buy milk');

  await ribbon(page, 'Tasks').click();
  const row = page.locator('.task-row', { hasText: `Buy milk ${name}` });
  await row.locator('input[type=checkbox]').click();
  await expect.poll(() => readNote(request, `${name}.md`)).toBe(`- [x] Buy milk ${name}\n- [ ] Call home ${name}\n`);
  // the tab lists open tasks: the ticked one moves to the "done" filter
  await expect(row).toHaveCount(0);
  await page.locator('.tasks-header button', { hasText: 'done' }).click();
  await expect(page.locator('.task-row.done', { hasText: `Buy milk ${name}` })).toBeVisible();

  await page.locator('.tab', { hasText: name }).click();
  await expect(editor(page)).toContainText('- [x] Buy milk');
});

test('dragging a kanban card into Done checks it off in the note', async ({ page, request }) => {
  const name = unique('Board');
  await createNote(request, `${name}.md`, `#kanban\n\n## To do\n\n- [ ] Card one ${name}\n- [ ] Card two ${name}\n\n## Done\n`);
  await page.goto('/');
  await ribbon(page, 'Kanban').click();

  // other tests may have made boards too: then a picker chooses, otherwise the only board is already showing
  await expect(page.locator('.kanban-header h2')).toBeVisible();
  const picker = page.locator('.kanban-header select');
  if (await picker.count()) {
    await picker.selectOption(`${name}.md`);
    await expect(picker).toHaveValue(`${name}.md`);
  } else {
    await expect(page.locator('.kanban-header h2')).toHaveText(name);
  }

  const card = page.locator('.kanban-card', { hasText: `Card one ${name}` });
  const done = page.locator('.kanban-column', { has: page.locator('h3', { hasText: 'Done' }) });
  await card.dragTo(done);

  await expect(done.locator('.kanban-card', { hasText: `Card one ${name}` })).toHaveClass(/done/);
  await expect(done.locator('h3')).toContainText('1');
  await expect.poll(() => readNote(request, `${name}.md`)).toBe(`#kanban\n\n## To do\n\n- [ ] Card two ${name}\n\n## Done\n\n- [x] Card one ${name}\n`);
});
