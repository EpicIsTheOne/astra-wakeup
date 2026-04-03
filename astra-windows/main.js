import { app, BrowserWindow, ipcMain, shell } from 'electron';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let mainWindow;
let panelWindow;

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 1360,
    height: 920,
    minWidth: 1100,
    minHeight: 760,
    backgroundColor: '#0b1020',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  mainWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'));
}

function createPanelWindow() {
  if (panelWindow && !panelWindow.isDestroyed()) {
    panelWindow.focus();
    return;
  }

  panelWindow = new BrowserWindow({
    width: 420,
    height: 620,
    minWidth: 360,
    minHeight: 420,
    frame: false,
    alwaysOnTop: true,
    resizable: true,
    transparent: false,
    backgroundColor: '#10172a',
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      additionalArguments: ['--astra-panel=true']
    }
  });

  panelWindow.loadFile(path.join(__dirname, 'renderer', 'index.html'), { hash: 'panel' });
  panelWindow.on('closed', () => {
    panelWindow = null;
  });
}

app.whenReady().then(() => {
  createMainWindow();

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createMainWindow();
  });
});

ipcMain.handle('astra:open-panel', () => {
  createPanelWindow();
  return { ok: true };
});

ipcMain.handle('astra:close-panel', () => {
  if (panelWindow && !panelWindow.isDestroyed()) panelWindow.close();
  return { ok: true };
});

ipcMain.handle('astra:window-minimize', (event) => {
  BrowserWindow.fromWebContents(event.sender)?.minimize();
  return { ok: true };
});

ipcMain.handle('astra:window-close', (event) => {
  BrowserWindow.fromWebContents(event.sender)?.close();
  return { ok: true };
});

ipcMain.handle('astra:open-external', (_event, url) => {
  if (typeof url === 'string' && url.startsWith('http')) shell.openExternal(url);
  return { ok: true };
});

ipcMain.handle('astra:app-info', () => ({
  ok: true,
  version: app.getVersion(),
  platform: process.platform,
  name: app.getName()
}));

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
