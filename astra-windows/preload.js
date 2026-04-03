import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('astraDesktop', {
  openPanel: () => ipcRenderer.invoke('astra:open-panel'),
  closePanel: () => ipcRenderer.invoke('astra:close-panel'),
  minimizeWindow: () => ipcRenderer.invoke('astra:window-minimize'),
  closeWindow: () => ipcRenderer.invoke('astra:window-close'),
  openExternal: (url) => ipcRenderer.invoke('astra:open-external', url),
  appInfo: () => ipcRenderer.invoke('astra:app-info'),
  updaterConfig: (config) => ipcRenderer.invoke('astra:updater-config', config),
  updaterCheck: () => ipcRenderer.invoke('astra:updater-check'),
  updaterState: () => ipcRenderer.invoke('astra:updater-state'),
  onUpdaterState: (callback) => {
    const listener = (_event, payload) => callback(payload);
    ipcRenderer.on('astra:updater-state', listener);
    return () => ipcRenderer.removeListener('astra:updater-state', listener);
  },
  isPanel: process.argv.includes('--astra-panel=true') || window.location.hash === '#panel'
});
