/**
 * Google Analytics Surrogate
 */
(function() {
  window.ga = window.ga || function() {
    (window.ga.q = window.ga.q || []).push(arguments);
  };
  window.ga.l = +new Date();
  window.GoogleAnalyticsObject = 'ga';
  window._gaq = window._gaq || [];
  window._gaq.push = function() {};
  window.gtag = window.gtag || function() {};
  window.dataLayer = window.dataLayer || [];
})();
