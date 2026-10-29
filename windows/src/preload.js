'use strict';
const { contextBridge, ipcRenderer } = require('electron');

const call = (ch) => (arg) => ipcRenderer.invoke(ch, arg);
const EVENTS = new Set(['browser:state', 'lock:changed', 'toast']);

// A narrow, explicit API. Credentials only ever travel renderer -> main (write-only); they are never sent back.
contextBridge.exposeInMainWorld('travelhub', {
  getState: call('state:get'),
  savePortal: call('portal:save'),
  deletePortal: (id) => ipcRenderer.invoke('portal:delete', { id }),
  modifyPortal: (id, patch) => ipcRenderer.invoke('portal:modify', { id, patch }),
  movePortal: (id, delta) => ipcRenderer.invoke('portal:move', { id, delta }),
  forgetLogin: (id) => ipcRenderer.invoke('portal:forgetLogin', { id }),
  clearPortalSession: (id) => ipcRenderer.invoke('portal:clearSession', { id }),
  openPortal: (id) => ipcRenderer.invoke('portal:open', { id }),
  openPortalExternal: (id) => ipcRenderer.invoke('portal:openExternal', { id }),
  browser: (action, submit) => ipcRenderer.invoke('browser:action', { action, submit }),
  updateSettings: call('settings:update'),
  unlock: (pin) => ipcRenderer.invoke('lock:unlock', { pin }),
  lockNow: call('lock:lock'),
  reauth: (pin) => ipcRenderer.invoke('lock:reauth', { pin }),
  setPin: (pin) => ipcRenderer.invoke('lock:setPin', { pin }),
  changePin: (current, next) => ipcRenderer.invoke('lock:changePin', { current, next }),
  removePin: (current) => ipcRenderer.invoke('lock:removePin', { current }),
  forgotPin: call('lock:forgot'),
  exportPortals: call('data:export'),
  importPortals: call('data:import'),
  clearBrowsingData: call('data:clearBrowsing'),
  deleteAllLogins: call('data:deleteCreds'),
  deleteEverything: call('data:deleteAll'),
  about: call('app:about'),
  on: (channel, cb) => {
    if (!EVENTS.has(channel)) return () => {};
    const h = (_e, payload) => cb(payload);
    ipcRenderer.on(channel, h);
    return () => ipcRenderer.removeListener(channel, h);
  },
});
