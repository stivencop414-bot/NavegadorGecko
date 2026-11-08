/**
 * PhantomShield - Surrogate Redirect Manager
 * Local compatibility stubs for exact tracker script endpoints. No telemetry is sent by these stubs.
 */
(function() {
  'use strict';

  const SURROGATE_MAP = [
    {
      host: "google-analytics.com", path: "/analytics.js",
      redirectUrl: browser.runtime.getURL("web-accessible/ga-surrogate.js")
    },
    {
      host: "googletagmanager.com", path: "/gtm.js",
      redirectUrl: browser.runtime.getURL("web-accessible/gtm-surrogate.js")
    },
    {
      host: "googletagservices.com", path: "/tag/js/gpt.js",
      redirectUrl: browser.runtime.getURL("web-accessible/noop.js")
    },
    {
      host: "connect.facebook.net", path: "/en_US/fbevents.js",
      redirectUrl: browser.runtime.getURL("web-accessible/noop.js")
    }
  ];

  class SurrogateRedirector {
    getSurrogateUrl(url) {
      if (!url) return null;
      let parsed;
      try { parsed = new URL(url); } catch (error) { return null; }
      if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') return null;
      for (let i = 0; i < SURROGATE_MAP.length; i++) {
        const entry = SURROGATE_MAP[i];
        if ((parsed.hostname === entry.host || parsed.hostname.endsWith('.' + entry.host)) && parsed.pathname === entry.path) {
          return SURROGATE_MAP[i].redirectUrl;
        }
      }
      return null;
    }
  }

  const redirector = new SurrogateRedirector();

  if (typeof window !== "undefined") {
    window.PhantomSurrogateRedirector = redirector;
  }
  if (typeof globalThis !== "undefined") {
    globalThis.PhantomSurrogateRedirector = redirector;
  }
})();
