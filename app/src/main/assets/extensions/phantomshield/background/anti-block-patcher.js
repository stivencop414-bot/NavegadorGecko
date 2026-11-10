/* Optional, exact-byte patches for two published MIT libraries. Unknown scripts pass unchanged. */
(function() {
  'use strict';
  const RULES = Object.freeze([
    {path:'/ajax/libs/blockadblock/3.2.1/blockadblock.min.js',hash:'df35392ebe2722ddcafc180639031db9a8ed65c3d5f5e94833fdb74435d1a77a',flag:'o'},
    {path:'/ajax/libs/fuckadblock/3.2.1/fuckadblock.min.js',hash:'c63c0a518fcd8243e365904eb4ec5162d2b6d066aa4f05027fb598089d73ebdc',flag:'i'}
  ]);
  const MAX_BYTES=131072; let active=0;
  function ruleFor(url) {
    try { const u=new URL(url);return u.protocol==='https:' && !u.username && !u.password && !u.search && !u.hash ? RULES.find(rule=>u.pathname===rule.path || u.pathname.endsWith('/'+rule.path.split('/').pop())) : undefined; } catch(_){return undefined}
  }
  async function rewrite(url, bytes) {
    const rule=ruleFor(url);
    if(!rule || bytes.byteLength>MAX_BYTES) return bytes;
    const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('');
    if(digest!==rule.hash) return bytes;
    const source=new TextDecoder().decode(bytes);
    const needle='}this._options.debug===!0&&this._log("_checkBait"';
    if(source.indexOf(needle)<0 || source.indexOf(needle)!==source.lastIndexOf(needle)) return bytes;
    return new TextEncoder().encode(source.replace(needle,'}'+rule.flag+'=!1;this._options.debug===!0&&this._log("_checkBait"'));
  }
  function intercept(details, enabled, patched) {
    if(details.type!=='script' || !ruleFor(details.url) || active>=8 || !browser.webRequest.filterResponseData || !enabled()) return false;
    let filter;
    try{filter=browser.webRequest.filterResponseData(details.requestId)}catch(_){return false}
    active++;let size=0,chunks=[],passthrough=false,finished=false;
    function finish() {if(!finished){finished=true;active--;try{filter.close()}catch(_){}}}
    function drain() {for(const chunk of chunks)filter.write(chunk);chunks=[]}
    filter.ondata=event=>{
      try {
        if(passthrough){filter.write(event.data);return}
        chunks.push(event.data);size+=event.data.byteLength;
        if(size>MAX_BYTES){passthrough=true;drain()}
      } catch(_){finish()}
    };
    filter.onstop=async()=>{
      try {
        if(!passthrough){
          const bytes=new Uint8Array(size);let offset=0;
          for(const chunk of chunks){bytes.set(new Uint8Array(chunk),offset);offset+=chunk.byteLength}
          const result=enabled()?await rewrite(details.url,bytes):bytes;
          const changed=enabled() && result!==bytes;
          filter.write(changed?result:bytes);chunks=[];
          if(changed)patched();
        }
      } catch(_){try{drain()}catch(__){}}
      finally{finish()}
    };
    filter.onerror=()=>{try{drain()}catch(_){}finish()};
    return true;
  }
  globalThis.PhantomAntiBlockPatcher={rewrite,intercept,ruleFor};
})();
