(async function() {
  try {
    const profile = await browser.runtime.sendNativeMessage('nexo_network', {phase:'profile'});
    const result = await browser.runtime.sendMessage({type:'APPLY_ROUTE', profile});
    await browser.runtime.sendNativeMessage('nexo_network', {phase:'ready', ok:result && result.ok === true});
  } catch (_) {
    document.body.textContent = 'No se pudo preparar la ruta. La navegación quedó bloqueada; vuelve a elegir la red de la pestaña.';
  }
})();
