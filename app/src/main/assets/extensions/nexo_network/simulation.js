/* Firefox isolated-world bridge. Simulations are best-effort API overrides, not anonymity. */
(function() {
  'use strict';
  if (!window.wrappedJSObject || typeof exportFunction !== 'function') return;
  const page = window.wrappedJSObject;
  const policy = browser.runtime.sendMessage({type:'ROUTE_POLICY'}).catch(() => ({geo:null,burner:false}));
  const geo = page.navigator.geolocation;
  if (geo) {
    const get = geo.getCurrentPosition, watch = geo.watchPosition, clear = geo.clearWatch;
    const watches = new Map(); let next = 0;
    const position = p => cloneInto({coords:{latitude:p.lat,longitude:p.lon,accuracy:25,altitude:null,altitudeAccuracy:null,heading:null,speed:null},timestamp:Date.now()},page);
    function call(success, error, options, record) {
      policy.then(p => {
        if (record && !watches.has(record.id)) return;
        if (p.geo) {
          if (typeof success === 'function') success(position(p.geo));
        } else if (record) record.nativeId = Reflect.apply(watch,geo,[success,error,options]);
        else Reflect.apply(get,geo,[success,error,options]);
      }).catch(function() {});
    }
    geo.getCurrentPosition = exportFunction(function(success,error,options) { call(success,error,options,null); },page);
    geo.watchPosition = exportFunction(function(success,error,options) {
      const record={id:--next}; watches.set(record.id,record); call(success,error,options,record); return record.id;
    },page);
    geo.clearWatch = exportFunction(function(id) {
      const record=watches.get(id);
      if(record) { watches.delete(id); if(record.nativeId !== undefined) Reflect.apply(clear,geo,[record.nativeId]); }
      else if(id >= 0) Reflect.apply(clear,geo,[id]);
    },page);
    policy.then(p => {
      if (!p.geo) {
        // Normal tabs retain native functions once pending calls have been delegated.
        geo.getCurrentPosition=get;
      }
    });
  }
  policy.then(p => {
    if(!p.burner || !page.HTMLCanvasElement || !page.CanvasRenderingContext2D) return;
    let seed=p.seed >>> 0;
    for(const c of String(location.hostname)) seed=Math.imul(seed ^ c.charCodeAt(0),16777619) >>> 0;
    const canvas=page.HTMLCanvasElement.prototype, ctx=page.CanvasRenderingContext2D.prototype;
    const read=ctx.getImageData, put=ctx.putImageData, data=canvas.toDataURL, blob=canvas.toBlob;
    function vary(image) {
      if(image && image.data && image.data.length >= 4) {
        const index=(seed % (image.data.length/4)) * 4;
        image.data[index] ^= 1;
      }
      return image;
    }
    ctx.getImageData=exportFunction(function(...args) { return vary(Reflect.apply(read,this,args)); },page);
    function copy(source) {
      if(!source.width || !source.height || source.width*source.height>1048576) return null;
      const target=page.document.createElement('canvas');target.width=source.width;target.height=source.height;
      const context=target.getContext('2d');context.drawImage(source,0,0);
      const image=vary(Reflect.apply(read,context,[0,0,target.width,target.height]));
      Reflect.apply(put,context,[image,0,0]);return target;
    }
    canvas.toDataURL=exportFunction(function(...args) {
      let target;try{target=copy(this)}catch(_){} return Reflect.apply(data,target||this,args);
    },page);
    if(blob) canvas.toBlob=exportFunction(function(...args) {
      let target;try{target=copy(this)}catch(_){} return Reflect.apply(blob,target||this,args);
    },page);
    const vendors=['Intel Inc.','Google Inc.','Mozilla'];
    const renderers=['ANGLE (Generic GPU)','WebGL Renderer','Generic Renderer'];
    for(const type of ['WebGLRenderingContext','WebGL2RenderingContext']) {
      const prototype=page[type] && page[type].prototype;
      if(!prototype || typeof prototype.getParameter !== 'function') continue;
      const original=prototype.getParameter;
      prototype.getParameter=exportFunction(function(parameter) {
        if(parameter===37445) return vendors[seed%vendors.length];
        if(parameter===37446) return renderers[(seed>>>8)%renderers.length];
        return Reflect.apply(original,this,[parameter]);
      },page);
    }
  }).catch(function() {});
})();
