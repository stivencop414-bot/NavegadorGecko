/**
 * Google Tag Manager Surrogate
 */
(function() {
  window.google_tag_manager = window.google_tag_manager || {};
  window.dataLayer = window.dataLayer || [];
  window.dataLayer.push = function(obj) {
    if (typeof obj === 'object' && obj.eventCallback) {
      setTimeout(obj.eventCallback, 0);
    }
    return Array.prototype.push.apply(this, arguments);
  };
})();
