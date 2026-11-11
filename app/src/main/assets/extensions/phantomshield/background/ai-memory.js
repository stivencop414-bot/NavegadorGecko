/* Local, bounded, opt-in memory. Stores only hashes and aggregate rule metadata. */
(function() {
  'use strict';
  const TTL = 30 * 86400000, LIMIT = 1000, PER_HOST = 50;
  class AIMemory {
    constructor() {
      this.entries = []; this.queue = Promise.resolve();
      this.ready = browser.storage.local.get({aiMemory: []}).then(async stored => {
        this.entries = this.prune(Array.isArray(stored.aiMemory) ? stored.aiMemory : []);
        if(Array.isArray(stored.aiMemory) && stored.aiMemory.length!==this.entries.length)await browser.storage.local.set({aiMemory:this.entries});
      });
      // A storage failure must not overwrite an unread database.
      this.ready.catch(function() {});
    }
    prune(entries) {
      const now = Date.now(), seen = new Set(), counts = new Map();
      return entries.filter(e => e && PhantomAIConfig.publicHost(e.host) && /^[a-f0-9]{64}$/.test(e.key) &&
        Number.isFinite(e.created) && e.created <= now && now-e.created < TTL &&
        Number.isFinite(e.lastUsed) && Number.isSafeInteger(e.hits) && e.hits >= 0)
        .sort((a,b) => b.lastUsed-a.lastUsed).filter(e => {
          const key=e.host+':'+e.key, count=counts.get(e.host)||0;
          if(seen.has(key) || count>=PER_HOST)return false;
          seen.add(key);counts.set(e.host,count+1);return true;
        }).slice(0,LIMIT).map(e=>({host:e.host,key:e.key,created:e.created,lastUsed:e.lastUsed,hits:e.hits}));
    }
    transaction(change) {
      const task=this.queue.catch(function(){}).then(async()=>{
        await this.ready;
        const next=this.prune(this.entries).map(e=>({...e}));
        const result=change(next);
        const saved=this.prune(next);
        await browser.storage.local.set({aiMemory:saved});
        this.entries=saved;return result;
      });
      this.queue=task;return task;
    }
    async key(signature) {
      const digest=await crypto.subtle.digest('SHA-256',new TextEncoder().encode(signature));
      return Array.from(new Uint8Array(digest),n=>n.toString(16).padStart(2,'0')).join('');
    }
    async find(host, keys) {
      await this.ready;await this.queue.catch(function(){});
      return new Set(this.prune(this.entries).filter(e=>e.host===host && keys.includes(e.key)).map(e=>e.key));
    }
    async remember(host, keys, valid) {
      if(!keys.length)return;
      return this.transaction(entries=>{
        if(!valid())return;
        for(const key of new Set(keys)) if(!entries.some(e=>e.host===host && e.key===key))
          entries.push({host,key,created:Date.now(),lastUsed:Date.now(),hits:0});
      });
    }
    async touch(host, keys, valid) {
      if(!keys.length)return;
      return this.transaction(entries=>{if(valid())for(const e of entries)if(e.host===host && keys.includes(e.key)){
        e.lastUsed=Date.now();e.hits=Math.min(Number.MAX_SAFE_INTEGER,e.hits+1);
      }});
    }
    forget(host) { return this.transaction(entries=>{
      for(let i=entries.length-1;i>=0;i--)if(!host || entries[i].host===host)entries.splice(i,1);
    }); }
    async summary() {
      await this.ready;await this.queue.catch(function(){});
      const groups=new Map();
      for(const entry of this.prune(this.entries)) {
        const group=groups.get(entry.host)||{host:entry.host,rules:0,hits:0,lastUsed:0};
        group.rules++;group.hits+=entry.hits;group.lastUsed=Math.max(group.lastUsed,entry.lastUsed);groups.set(entry.host,group);
      }
      return {domains:[...groups.values()].sort((a,b)=>b.lastUsed-a.lastUsed),limit:LIMIT,days:30};
    }
  }
  globalThis.PhantomAIMemory = new AIMemory();
})();
