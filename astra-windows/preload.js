import { contextBridge, ipcRenderer } from 'electron';

contextBridge.exposeInMainWorld('astraDesktop', {
  openPanel: () => ipcRenderer.invoke('astra:open-panel'),
  closePanel: () => ipcRenderer.invoke('astra:close-panel'),
  minimizeWindow: () => ipcRenderer.invoke('astra:window-minimize'),
  closeWindow: () => ipcRenderer.invoke('astra:window-close'),
  openExternal: (url) => ipcRenderer.invoke('astra:open-external', url),
  appInfo: () => ipcRenderer.invoke('astra:app-info'),
  isPanel: process.argv.includes('--astra-panel=true') || window.location.hash === '#panel'
});
