import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';

/** Tests run in parallel against one vault, so every note they make gets its own name. */
export const unique = (name: string) => `${name} ${Math.random().toString(36).slice(2, 8)}`;

export async function createNote(request: APIRequestContext, path: string, content: string): Promise<void> {
  const response = await request.post('/api/notes', { data: { path, content } });
  expect(response.ok(), `create ${path}: ${response.status()}`).toBeTruthy();
}

export async function readNote(request: APIRequestContext, path: string): Promise<string> {
  const response = await request.get(`/api/notes?path=${encodeURIComponent(path)}`);
  expect(response.ok(), `read ${path}: ${response.status()}`).toBeTruthy();
  return ((await response.json()) as { content: string }).content;
}

export async function noteExists(request: APIRequestContext, path: string): Promise<boolean> {
  return (await request.get(`/api/notes?path=${encodeURIComponent(path)}`)).ok();
}

/** Opens a note through the URL, the way a bookmark or a reload does. */
export async function openNote(page: Page, path: string): Promise<void> {
  await page.goto(`/#/${encodeURI(path)}`);
  await expect(activeTab(page)).toHaveAttribute('title', path);
}

export const editor = (page: Page): Locator => page.locator('.cm-content');
export const preview = (page: Page): Locator => page.locator('.preview');
export const activeTab = (page: Page): Locator => page.locator('.tab.active');
export const activeTitle = (page: Page): Locator => page.locator('.tab.active .tab-title');
/** The autosave state in the status bar: Saved, Unsaved changes, Saving… */
export const saveState = (page: Page): Locator => page.locator('.status-bar > span').last();

export async function typeInEditor(page: Page, text: string): Promise<void> {
  await editor(page).click();
  await page.keyboard.type(text);
}

/** Waits for the autosave after a change: it reports the change first, then the save. */
export async function waitSaved(page: Page): Promise<void> {
  await expect(saveState(page)).toHaveText('Saved');
}

export const ribbon = (page: Page, title: string): Locator => page.locator(`nav.ribbon button[title="${title}"]`);
