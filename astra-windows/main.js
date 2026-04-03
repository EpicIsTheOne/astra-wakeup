import { app, BrowserWindow, ipcMain, shell } from 'electron';
import { autoUpdater } from 'electron-updater';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

let mainWindow;
let panelWindow;
let updaterState = {
  phase: 'idle',
  message: 'Updater idle',
  progressPercent: 0,
  downloadedVersion: null,
  availableVersion: null,
  allowPrerelease: false,
  error: null,
  updateInfo: null
};
let installAfterDownload = true;

function sendUpdaterState() {
  for (const win of BrowserWindow.getAllWindows()) {
    if (!win.isDestroyed()) win.webContents.send('astra:updater-state', updaterState);
  }
}

function setUpdaterState(patch) {
  updaterState = { ...updaterState, ...patch };
  sendUpdaterState();
}

function configureAutoUpdater() {
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.disableWebInstaller = false;
  autoUpdater.allowDowngrade = false;

  autoUpdater.on('checking-for-update', () => {
    setUpdaterState({ phase: 'checking', message: 'Checking for updates…', progressPercent: 0, error: null });
  });

  autoUpdater.on('update-available', (info) => {
    setUpdaterState({
      phase: 'downloading',
      message: `Downloading ${info.version}…`,
      availableVersion: info.version || null,
      updateInfo: info,
      error: null
    });
  });

  autoUpdater.on('update-not-available', (info) => {
    setUpdaterState({
      phase: 'idle',
      message: `Already up to date${info?.version ? ` (${info.version})` : ''}.`,
      availableVersion: info?.version || null,
      progressPercent: 100,
      updateInfo: info || null,
      error: null
    });
  });

  autoUpdater.on('download-progress', (progress) => {
    setUpdaterState({
      phase: 'downloading',
      message: `Downloading update… ${Math.round(progress.percent || 0)}%`,
      progressPercent: Number(progress.percent || 0),
      error: null
    });
  });

  autoUpdater.on('update-downloaded', (info) => {
    setUpdaterState({
      phase: 'downloaded',
      message: `Update ${info.version} downloaded. Restarting to apply…`,
      downloadedVersion: info.version || null,
      progressPercent: 100,
      updateInfo: info,
      error: null
    });
    if (installAfterDownload) {
      setTimeout(() => {
        autoUpdater.quitAndInstall(false, true);
      }, 1800);
    }
  });

  autoUpdater.on('error', (error) => {
    setUpdaterState({
      phase: 'error',
      message: `Updater error: ${error?.message || 'unknown error'}`,
      error: error?.message || 'unknown error'
    });
  });
}

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
  configureAutoUpdater();
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

ipcMain.handle('astra:updater-config', async (_event, config = {}) => {
  autoUpdater.allowPrerelease = Boolean(config.allowPrerelease);
  installAfterDownload = config.autoApply !== false;
  setUpdaterState({ allowPrerelease: autoUpdater.allowPrerelease });
  return { ok: true, state: updaterState };
});

ipcMain.handle('astra:updater-check', async () => {
  try {
    const result = await autoUpdater.checkForUpdates();
    return { ok: true, state: updaterState, result: result?.updateInfo || null };
  } catch (error) {
    setUpdaterState({ phase: 'error', message: `Updater error: ${error?.message || 'unknown error'}`, error: error?.message || 'unknown error' });
    return { ok: false, error: error?.message || 'unknown error', state: updaterState };
  }
});

ipcMain.handle('astra:updater-state', () => ({ ok: true, state: updaterState }));

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
