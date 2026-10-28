import { expect, test } from '@playwright/test';
import { readNote, ribbon, unique } from './helpers';

test('dragging across days adds a multi-day event to Calendar.md', async ({ page, request }) => {
  const title = unique('Trip');
  await page.goto('/');
  await ribbon(page, 'Calendar').click();

  const cells = page.locator('.calendar-cell:not(.outside)');
  await expect(cells.first()).toBeVisible();
  const from = cells.nth(8);
  const to = cells.nth(10);
  const start = await from.getAttribute('data-day');
  const end = await to.getAttribute('data-day');
  const a = (await from.boundingBox())!;
  const b = (await to.boundingBox())!;

  await page.mouse.move(a.x + a.width / 2, a.y + a.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2, { steps: 8 });
  await expect(page.locator('.calendar-cell.in-range')).toHaveCount(3);
  await page.mouse.up();

  const form = page.locator('.calendar-form');
  await expect(form).toBeVisible();
  await form.locator('input[placeholder="What?"]').fill(title);
  await page.keyboard.press('Enter');

  await expect(page.locator(`.calendar-cell[data-day="${start}"] .calendar-pills`)).toContainText(title);
  await expect(page.locator(`.calendar-cell[data-day="${end}"] .calendar-pills`)).toContainText(title);
  await expect.poll(() => readNote(request, 'Calendar.md')).toContain(`${start}..${end} ${title}`);
});
