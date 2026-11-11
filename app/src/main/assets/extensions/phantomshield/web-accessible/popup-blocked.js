(function() {
  'use strict';
  // Una ventana abierta por una página puede cerrarse sola; si no, queda el aviso.
  const close = function() { try { window.close(); } catch (error) {} };
  document.getElementById('close').addEventListener('click', close);
  setTimeout(close, 350);
})();
